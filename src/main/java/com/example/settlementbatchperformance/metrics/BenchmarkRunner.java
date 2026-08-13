package com.example.settlementbatchperformance.metrics;

import com.example.settlementbatchperformance.job.PagingSettlementJobConfig;
import com.example.settlementbatchperformance.job.ZeroOffsetSettlementJobConfig;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "benchmark.enabled", havingValue = "true")
public class BenchmarkRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final JobOperator jobOperator;
    private final Job pagingJob;
    private final Job zeroOffsetJob;
    private final JdbcTemplate jdbcTemplate;
    private final String benchmarkId;
    private final long expectedDatasetSize;

    public BenchmarkRunner(
            JobOperator jobOperator,
            @Qualifier(PagingSettlementJobConfig.JOB_NAME) Job pagingJob,
            @Qualifier(ZeroOffsetSettlementJobConfig.JOB_NAME) Job zeroOffsetJob,
            JdbcTemplate jdbcTemplate,
            @Value("${benchmark.id}") String benchmarkId,
            @Value("${benchmark.dataset-size:100000}") long expectedDatasetSize) {
        this.jobOperator = jobOperator;
        this.pagingJob = pagingJob;
        this.zeroOffsetJob = zeroOffsetJob;
        this.jdbcTemplate = jdbcTemplate;
        this.benchmarkId = benchmarkId;
        this.expectedDatasetSize = expectedDatasetSize;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateFixedConditions();
        long actualDatasetSize = countSettlements();
        if (actualDatasetSize != expectedDatasetSize) {
            throw new IllegalStateException(
                    "Expected " + expectedDatasetSize + " settlements but found "
                            + actualDatasetSize);
        }

        log.info(
                "BENCHMARK_SESSION|benchmarkId={}|datasetSize={}|chunkSize={}|pageSize={}"
                        + "|fetchSize={}|schedule=PAGING,ZERO_OFFSET,PAGING,ZERO_OFFSET,"
                        + "ZERO_OFFSET,PAGING,PAGING,ZERO_OFFSET",
                benchmarkId,
                actualDatasetSize,
                PagingSettlementJobConfig.CHUNK_SIZE,
                PagingSettlementJobConfig.PAGE_SIZE,
                PagingSettlementJobConfig.FETCH_SIZE);

        for (RunSpec runSpec : runPlan()) {
            execute(runSpec, actualDatasetSize);
        }
    }

    static List<RunSpec> runPlan() {
        return List.of(
                new RunSpec(1, Phase.WARMUP, 0, ReaderType.PAGING),
                new RunSpec(2, Phase.WARMUP, 0, ReaderType.ZERO_OFFSET),
                new RunSpec(3, Phase.MEASURED, 1, ReaderType.PAGING),
                new RunSpec(4, Phase.MEASURED, 1, ReaderType.ZERO_OFFSET),
                new RunSpec(5, Phase.MEASURED, 2, ReaderType.ZERO_OFFSET),
                new RunSpec(6, Phase.MEASURED, 2, ReaderType.PAGING),
                new RunSpec(7, Phase.MEASURED, 3, ReaderType.PAGING),
                new RunSpec(8, Phase.MEASURED, 3, ReaderType.ZERO_OFFSET));
    }

    private void execute(RunSpec runSpec, long datasetSize) throws Exception {
        Job job = runSpec.readerType() == ReaderType.PAGING ? pagingJob : zeroOffsetJob;
        JobParameters parameters = new JobParametersBuilder()
                .addString("benchmark.id", benchmarkId)
                .addString("benchmark.phase", runSpec.phase().name())
                .addLong("benchmark.round", (long) runSpec.round())
                .addLong("benchmark.sequence", (long) runSpec.sequence())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        long wallStartedAt = System.nanoTime();
        BenchmarkPageLog.beginRun(
                benchmarkId,
                runSpec.phase().name(),
                runSpec.round(),
                runSpec.sequence(),
                runSpec.readerType().name());
        JobExecution jobExecution;
        try {
            jobExecution = jobOperator.start(job, parameters);
        } finally {
            BenchmarkPageLog.endRun();
        }
        long wallDurationNanos = System.nanoTime() - wallStartedAt;
        StepExecution stepExecution = jobExecution.getStepExecutions().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No StepExecution was created"));

        String failure = jobExecution.getAllFailureExceptions().stream()
                .findFirst()
                .map(Throwable::toString)
                .map(BenchmarkRunner::sanitize)
                .orElse("");
        log.info(
                "BENCHMARK_RESULT|benchmarkId={}|phase={}|round={}|sequence={}|reader={}"
                        + "|jobExecutionId={}|status={}|jobDurationNanos={}"
                        + "|stepDurationNanos={}|wallDurationNanos={}|readCount={}"
                        + "|writeCount={}|skipCount={}|rollbackCount={}|failure={}",
                benchmarkId,
                runSpec.phase(),
                runSpec.round(),
                runSpec.sequence(),
                runSpec.readerType(),
                jobExecution.getId(),
                jobExecution.getStatus(),
                durationNanos(jobExecution.getStartTime(), jobExecution.getEndTime()),
                durationNanos(stepExecution.getStartTime(), stepExecution.getEndTime()),
                wallDurationNanos,
                stepExecution.getReadCount(),
                stepExecution.getWriteCount(),
                stepExecution.getSkipCount(),
                stepExecution.getRollbackCount(),
                failure);

        if (jobExecution.getStatus() != BatchStatus.COMPLETED
                || stepExecution.getReadCount() != datasetSize
                || stepExecution.getWriteCount() != datasetSize
                || stepExecution.getSkipCount() != 0
                || stepExecution.getRollbackCount() != 0) {
            throw new IllegalStateException(
                    "Benchmark run failed validation: sequence=" + runSpec.sequence());
        }
    }

    private void validateFixedConditions() {
        boolean sameSizes = PagingSettlementJobConfig.CHUNK_SIZE
                        == ZeroOffsetSettlementJobConfig.CHUNK_SIZE
                && PagingSettlementJobConfig.PAGE_SIZE
                        == ZeroOffsetSettlementJobConfig.PAGE_SIZE
                && PagingSettlementJobConfig.FETCH_SIZE
                        == ZeroOffsetSettlementJobConfig.FETCH_SIZE;
        if (!sameSizes
                || PagingSettlementJobConfig.CHUNK_SIZE != 1_000
                || PagingSettlementJobConfig.PAGE_SIZE != 1_000
                || PagingSettlementJobConfig.FETCH_SIZE != 1_000) {
            throw new IllegalStateException("Benchmark size conditions must all equal 1,000");
        }
    }

    private long countSettlements() {
        Long count = jdbcTemplate.queryForObject("select count(*) from settlement", Long.class);
        return count == null ? 0 : count;
    }

    private static long durationNanos(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return -1;
        }
        return Duration.between(start, end).toNanos();
    }

    private static String sanitize(String value) {
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    enum Phase {
        WARMUP,
        MEASURED
    }

    enum ReaderType {
        PAGING,
        ZERO_OFFSET
    }

    record RunSpec(int sequence, Phase phase, int round, ReaderType readerType) {
    }
}
