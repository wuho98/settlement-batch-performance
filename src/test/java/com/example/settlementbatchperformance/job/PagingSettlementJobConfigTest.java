package com.example.settlementbatchperformance.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PagingSettlementJobConfigTest {

    private final JobOperator jobOperator;
    private final Job pagingSettlementJob;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    PagingSettlementJobConfigTest(
            JobOperator jobOperator,
            @Qualifier(PagingSettlementJobConfig.JOB_NAME) Job pagingSettlementJob,
            JdbcTemplate jdbcTemplate) {
        this.jobOperator = jobOperator;
        this.pagingSettlementJob = pagingSettlementJob;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void clearSettlements() {
        jdbcTemplate.update("delete from settlement");
    }

    @Test
    void completesWithEmptyTable() throws Exception {
        long sourceCount = countSettlements();

        StepExecution stepExecution = launchJob();

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getReadCount()).isEqualTo(sourceCount);
        assertThat(stepExecution.getWriteCount()).isEqualTo(sourceCount);
    }

    @Test
    void readsAndWritesEverySettlementWhenDataIsSmallerThanPageSize() throws Exception {
        insertSettlements(7);
        long sourceCount = countSettlements();

        StepExecution stepExecution = launchJob();

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getReadCount()).isEqualTo(sourceCount);
        assertThat(stepExecution.getWriteCount()).isEqualTo(sourceCount);
        assertThat(stepExecution.getSkipCount()).isZero();
    }

    @Test
    void readsAndWritesEverySettlementAcrossPages() throws Exception {
        insertSettlements(PagingSettlementJobConfig.PAGE_SIZE + 7);
        long sourceCount = countSettlements();

        StepExecution stepExecution = launchJob();

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getReadCount()).isEqualTo(sourceCount);
        assertThat(stepExecution.getWriteCount()).isEqualTo(sourceCount);
        assertThat(stepExecution.getSkipCount()).isZero();
    }

    private long countSettlements() {
        Long count = jdbcTemplate.queryForObject("select count(*) from settlement", Long.class);
        return count == null ? 0 : count;
    }

    private StepExecution launchJob() throws Exception {
        JobExecution execution = jobOperator.start(
                pagingSettlementJob,
                new JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        return stepExecutions.stream().findFirst().orElseThrow();
    }

    private void insertSettlements(int count) {
        LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbcTemplate.batchUpdate(
                "insert into settlement "
                        + "(id, merchant_id, status, amount, settled_at, created_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                java.util.stream.LongStream.rangeClosed(1, count).boxed().toList(),
                count,
                (statement, id) -> {
                    statement.setLong(1, id);
                    statement.setLong(2, id % 10 + 1);
                    statement.setString(3, "COMPLETED");
                    statement.setBigDecimal(4, BigDecimal.valueOf(id));
                    statement.setTimestamp(5, Timestamp.valueOf(baseTime.plusSeconds(id)));
                    statement.setTimestamp(6, Timestamp.valueOf(baseTime));
                });
    }
}
