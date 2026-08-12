package com.example.settlementbatchperformance.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.settlementbatchperformance.domain.Settlement;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.jpa.HibernateHints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
class ReaderRestartConsistencyTest {

    private static final int CHUNK_SIZE = 3;
    private static final int PAGE_SIZE = 3;
    private static final List<Long> SOURCE_IDS = List.of(1L, 2L, 5L, 8L, 13L, 21L, 34L);

    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbcTemplate;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    ReaderRestartConsistencyTest(
            EntityManagerFactory entityManagerFactory,
            JdbcTemplate jdbcTemplate,
            JobOperator jobOperator,
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager) {
        this.entityManagerFactory = entityManagerFactory;
        this.jdbcTemplate = jdbcTemplate;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    @BeforeEach
    void prepareTables() {
        jdbcTemplate.execute(
                "create table if not exists processed_settlement "
                        + "(settlement_id bigint not null primary key)");
        jdbcTemplate.update("delete from processed_settlement");
        jdbcTemplate.update("delete from settlement");
        insertSettlements(SOURCE_IDS);
    }

    @ParameterizedTest
    @EnumSource(ReaderType.class)
    void restartAfterFailureProcessesEveryIdExactlyOnce(ReaderType readerType) throws Exception {
        AtomicInteger writeCount = new AtomicInteger();
        ItemStreamReader<Settlement> reader = readerType.createReader(entityManagerFactory);
        ItemWriter<Settlement> writer = chunk -> {
            jdbcTemplate.batchUpdate(
                    "insert into processed_settlement (settlement_id) values (?)",
                    chunk.getItems(),
                    chunk.size(),
                    (statement, settlement) -> statement.setLong(1, settlement.getId()));

            if (writeCount.incrementAndGet() == 2) {
                throw new IllegalStateException("intentional failure after the second chunk");
            }
        };
        Job job = createRestartableJob(readerType, reader, writer);
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        var failedExecution = jobOperator.start(job, parameters);

        assertThat(failedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(processedIds()).containsExactly(1L, 2L, 5L);

        var restartedExecution = jobOperator.start(job, parameters);

        assertThat(restartedExecution.getId()).isNotEqualTo(failedExecution.getId());
        assertThat(restartedExecution.getJobInstanceId())
                .isEqualTo(failedExecution.getJobInstanceId());
        assertThat(restartedExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(processedIds()).containsExactlyElementsOf(SOURCE_IDS).doesNotHaveDuplicates();
    }

    private Job createRestartableJob(
            ReaderType readerType,
            ItemStreamReader<Settlement> reader,
            ItemWriter<Settlement> writer) {
        String suffix = readerType.name().toLowerCase();
        Step step = new StepBuilder("restartConsistencyStep-" + suffix, jobRepository)
                .<Settlement, Settlement>chunk(CHUNK_SIZE)
                .reader(reader)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
        return new JobBuilder("restartConsistencyJob-" + suffix, jobRepository)
                .start(step)
                .build();
    }

    private List<Long> processedIds() {
        return jdbcTemplate.queryForList(
                "select settlement_id from processed_settlement order by settlement_id",
                Long.class);
    }

    private void insertSettlements(List<Long> ids) {
        LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbcTemplate.batchUpdate(
                "insert into settlement "
                        + "(id, merchant_id, status, amount, settled_at, created_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                ids,
                ids.size(),
                (statement, id) -> {
                    statement.setLong(1, id);
                    statement.setLong(2, id % 10 + 1);
                    statement.setString(3, "COMPLETED");
                    statement.setBigDecimal(4, BigDecimal.valueOf(id));
                    statement.setTimestamp(5, Timestamp.valueOf(baseTime.plusSeconds(id)));
                    statement.setTimestamp(6, Timestamp.valueOf(baseTime));
                });
    }

    private enum ReaderType {
        PAGING {
            @Override
            ItemStreamReader<Settlement> createReader(EntityManagerFactory factory) {
                return new JpaPagingItemReaderBuilder<Settlement>()
                        .name("restartConsistencyPagingReader")
                        .entityManagerFactory(factory)
                        .queryString("select s from Settlement s order by s.id asc")
                        .hintValues(Map.of(HibernateHints.HINT_FETCH_SIZE, PAGE_SIZE))
                        .pageSize(PAGE_SIZE)
                        .saveState(true)
                        .build();
            }
        },
        ZERO_OFFSET {
            @Override
            ItemStreamReader<Settlement> createReader(EntityManagerFactory factory) {
                return new ZeroOffsetItemReader(
                        "restartConsistencyZeroOffsetReader", factory, PAGE_SIZE, PAGE_SIZE);
            }
        };

        abstract ItemStreamReader<Settlement> createReader(EntityManagerFactory factory);
    }
}
