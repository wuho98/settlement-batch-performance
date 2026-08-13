#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "${script_dir}/.." && pwd)"
cd "${project_dir}"

dataset_size="${1:-}"
case "${dataset_size}" in
    100000)
        dataset_label="100k"
        ;;
    500000)
        dataset_label="500k"
        ;;
    1000000)
        dataset_label="1m"
        ;;
    *)
        echo "Usage: $0 <100000|500000|1000000> [benchmark-id]" >&2
        exit 1
        ;;
esac

benchmark_id="${2:-$(TZ=Asia/Seoul date +%Y%m%d-%H%M%S)-${dataset_label}}"
benchmark_db="settlement_benchmark_${dataset_label}"
result_dir="results/${benchmark_id}"
raw_dir="${result_dir}/raw"

java_home_version=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    java_home_version="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1)"
fi

if [[ -n "${BENCHMARK_JAVA_HOME:-}" ]]; then
    benchmark_java_home="${BENCHMARK_JAVA_HOME}"
elif [[ "${java_home_version}" == *'version "21.'* ]]; then
    benchmark_java_home="${JAVA_HOME}"
elif [[ -x "/usr/libexec/java_home" ]]; then
    benchmark_java_home="$(/usr/libexec/java_home -v 21)"
else
    echo "Java 21 path is required. Set BENCHMARK_JAVA_HOME or JAVA_HOME." >&2
    exit 1
fi

if [[ ! -x "${benchmark_java_home}/bin/java" ]]; then
    echo "Java 21 was not found: ${benchmark_java_home}" >&2
    exit 1
fi

benchmark_java_version="$("${benchmark_java_home}/bin/java" -version 2>&1 | head -n 1)"
if [[ "${benchmark_java_version}" != *'version "21.'* ]]; then
    echo "Java 21 is required, but found: ${benchmark_java_version}" >&2
    exit 1
fi

export JAVA_HOME="${benchmark_java_home}"

if [[ -e "${result_dir}" ]]; then
    echo "Result directory already exists: ${result_dir}" >&2
    exit 1
fi

mkdir -p "${raw_dir}"

docker compose up -d --wait > "${raw_dir}/docker-start.log" 2>&1
docker compose exec -T -e MYSQL_PWD=root mysql \
    mysql -uroot \
    -e "CREATE DATABASE IF NOT EXISTS ${benchmark_db} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON ${benchmark_db}.* TO 'settlement'@'%';" \
    > "${raw_dir}/benchmark-database.log" 2>&1

{
    echo "benchmark_id=${benchmark_id}"
    echo "benchmark_database=${benchmark_db}"
    echo "dataset_size=${dataset_size}"
    echo "captured_at=$(TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S%z)"
    echo "git_commit=$(git rev-parse HEAD)"
    echo "git_branch=$(git branch --show-current)"
    echo "worktree_dirty=$(test -n "$(git status --porcelain)" && echo true || echo false)"
    echo "os=$(uname -a)"
    echo "java_home=${benchmark_java_home}"
    echo "java_version=${benchmark_java_version}"
    echo "docker_server_version=$(docker version --format '{{.Server.Version}}')"
    echo "mysql_image=$(docker inspect settlement-batch-mysql --format '{{.Config.Image}}')"
    docker compose exec -T -e MYSQL_PWD=settlement mysql \
        mysql -N -B -usettlement "${benchmark_db}" \
        -e "select concat('mysql_version=', version());"
    case "$(uname -s)" in
        Darwin)
            echo "cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
            echo "logical_cpu=$(sysctl -n hw.logicalcpu 2>/dev/null || true)"
            echo "memory_bytes=$(sysctl -n hw.memsize 2>/dev/null || true)"
            ;;
        Linux)
            echo "cpu=$(awk -F: '/model name/ { sub(/^[[:space:]]+/, "", $2); print $2; exit }' /proc/cpuinfo)"
            echo "logical_cpu=$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
            echo "memory_bytes=$(awk '/MemTotal/ { printf "%.0f", $2 * 1024 }' /proc/meminfo)"
            ;;
    esac
} > "${result_dir}/environment.txt"

git status --short > "${result_dir}/source-status.txt"
git diff --binary > "${result_dir}/source-diff.patch"
tar -czf "${result_dir}/source-snapshot.tar.gz" \
    build.gradle settings.gradle gradle src/main database scripts

docker compose exec -T -e MYSQL_PWD=settlement mysql \
    mysql -usettlement "${benchmark_db}" \
    < database/init/001-schema.sql \
    > "${raw_dir}/schema.log" 2>&1
docker compose exec -T -e MYSQL_PWD=settlement mysql \
    mysql --init-command="SET @dataset_size=${dataset_size}" \
    -usettlement "${benchmark_db}" \
    < database/generate-dataset.sql \
    > "${raw_dir}/dataset-generation.log" 2>&1
docker compose exec -T -e MYSQL_PWD=settlement mysql \
    mysql --init-command="SET @expected_dataset_size=${dataset_size}" \
    -usettlement "${benchmark_db}" \
    < database/verify-dataset.sql \
    > "${raw_dir}/dataset-verification.log" 2>&1

./gradlew bootRun --args="--spring.batch.job.enabled=false --benchmark.enabled=true --benchmark.id=${benchmark_id} --benchmark.dataset-size=${dataset_size} --spring.datasource.url=jdbc:mysql://localhost:3307/${benchmark_db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true" \
    > "${raw_dir}/benchmark.log" 2>&1

grep 'BENCHMARK_SESSION\|BENCHMARK_RESULT\|BENCHMARK_PAGE' \
    "${raw_dir}/benchmark.log" > "${result_dir}/metrics.log"

measured_count=$(grep -c 'BENCHMARK_RESULT.*phase=MEASURED' "${result_dir}/metrics.log")
completed_count=$(grep -c 'BENCHMARK_RESULT.*status=COMPLETED' "${result_dir}/metrics.log")
valid_count=$(awk -v size="${dataset_size}" \
    '/BENCHMARK_RESULT/ && index($0, "readCount=" size "|writeCount=" size "|skipCount=0|rollbackCount=0") { count++ } END { print count + 0 }' \
    "${result_dir}/metrics.log")
expected_page_count=$((dataset_size / 1000 + 1))
page_count=$(grep -c 'BENCHMARK_PAGE' "${result_dir}/metrics.log")

if [[ "${measured_count}" -ne 6 \
    || "${completed_count}" -ne 8 \
    || "${valid_count}" -ne 8 \
    || "${page_count}" -ne $((expected_page_count * 8)) ]]; then
    echo "Benchmark validation failed: measured=${measured_count}, completed=${completed_count}, valid=${valid_count}, pages=${page_count}" >&2
    exit 1
fi

echo "Benchmark completed: ${result_dir}"
