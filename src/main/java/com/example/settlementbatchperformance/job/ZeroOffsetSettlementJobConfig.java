package com.example.settlementbatchperformance.job;

import com.example.settlementbatchperformance.domain.Settlement;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ZeroOffsetSettlementJobConfig {

    public static final String JOB_NAME = "zeroOffsetSettlementJob";
    public static final String STEP_NAME = "zeroOffsetSettlementStep";
    public static final String READER_NAME = "zeroOffsetSettlementReader";
    public static final int CHUNK_SIZE = 1_000;
    public static final int PAGE_SIZE = 1_000;
    public static final int FETCH_SIZE = 1_000;

    @Bean
    @StepScope
    public ItemStreamReader<Settlement> zeroOffsetSettlementReader(
            EntityManagerFactory entityManagerFactory,
            @Value("${benchmark.enabled:false}") boolean benchmarkMetricsEnabled) {
        return new ZeroOffsetItemReader(
                READER_NAME,
                entityManagerFactory,
                PAGE_SIZE,
                FETCH_SIZE,
                benchmarkMetricsEnabled);
    }

    @Bean
    public ItemWriter<Settlement> zeroOffsetSettlementWriter() {
        return chunk -> {
            // no-op: keep writer I/O identical to the paging comparison job.
        };
    }

    @Bean
    public Step zeroOffsetSettlementStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemStreamReader<Settlement> zeroOffsetSettlementReader,
            ItemWriter<Settlement> zeroOffsetSettlementWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Settlement, Settlement>chunk(CHUNK_SIZE)
                .reader(zeroOffsetSettlementReader)
                .writer(zeroOffsetSettlementWriter)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    public Job zeroOffsetSettlementJob(
            JobRepository jobRepository, Step zeroOffsetSettlementStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(zeroOffsetSettlementStep)
                .build();
    }
}
