#!/usr/bin/env bash

set -euo pipefail

benchmark_id="${1:-$(TZ=Asia/Seoul date +%Y%m%d-%H%M%S)}"
benchmark_db="settlement_benchmark_100k"
result_dir="results/${benchmark_id}"
raw_dir="${result_dir}/raw"
benchmark_java_home="${BENCHMARK_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

if [[ ! -x "${benchmark_java_home}/bin/java" ]]; then
    echo "Java 21 was not found: ${benchmark_java_home}" >&2
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
    echo "captured_at=$(TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S%z)"
    echo "git_commit=$(git rev-parse HEAD)"
    echo "git_branch=$(git branch --show-current)"
    echo "worktree_dirty=$(test -n "$(git status --porcelain)" && echo true || echo false)"
    echo "os=$(uname -a)"
    echo "java_home=${benchmark_java_home}"
    echo "java_version=$("${benchmark_java_home}/bin/java" -version 2>&1 | head -n 1)"
    echo "docker_server_version=$(docker version --format '{{.Server.Version}}')"
    echo "mysql_image=$(docker inspect settlement-batch-mysql --format '{{.Config.Image}}')"
    docker compose exec -T -e MYSQL_PWD=settlement mysql \
        mysql -N -B -usettlement "${benchmark_db}" \
        -e "select concat('mysql_version=', version());"
    if command -v sysctl >/dev/null 2>&1; then
        echo "cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
        echo "logical_cpu=$(sysctl -n hw.logicalcpu 2>/dev/null || true)"
        echo "memory_bytes=$(sysctl -n hw.memsize 2>/dev/null || true)"
    fi
} > "${result_dir}/environment.txt"

git diff --binary > "${result_dir}/source-diff.patch"

docker compose exec -T -e MYSQL_PWD=settlement mysql \
    mysql -usettlement "${benchmark_db}" \
    < database/init/001-schema.sql \
    > "${raw_dir}/schema.log" 2>&1
docker compose exec -T -e MYSQL_PWD=settlement mysql \
    mysql -usettlement "${benchmark_db}" \
    < database/generate-100k.sql \
    > "${raw_dir}/dataset-generation.log" 2>&1
docker compose exec -T -e MYSQL_PWD=settlement mysql \
    mysql -usettlement "${benchmark_db}" \
    < database/verify-100k.sql \
    > "${raw_dir}/dataset-verification.log" 2>&1

./gradlew bootRun --args="--spring.batch.job.enabled=false --benchmark.enabled=true --benchmark.id=${benchmark_id} --benchmark.dataset-size=100000 --spring.datasource.url=jdbc:mysql://localhost:3307/${benchmark_db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true" \
    > "${raw_dir}/benchmark.log" 2>&1

grep 'BENCHMARK_SESSION\|BENCHMARK_RESULT\|BENCHMARK_PAGE' \
    "${raw_dir}/benchmark.log" > "${result_dir}/metrics.log"

measured_count=$(grep -c 'BENCHMARK_RESULT.*phase=MEASURED' "${result_dir}/metrics.log")
completed_count=$(grep -c 'BENCHMARK_RESULT.*status=COMPLETED' "${result_dir}/metrics.log")
if [[ "${measured_count}" -ne 6 || "${completed_count}" -ne 8 ]]; then
    echo "Benchmark validation failed: measured=${measured_count}, completed=${completed_count}" >&2
    exit 1
fi

echo "Benchmark completed: ${result_dir}"
