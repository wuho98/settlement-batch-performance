package com.example.settlementbatchperformance.job;

import com.example.settlementbatchperformance.domain.Settlement;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import org.hibernate.jpa.HibernateHints;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PagingSettlementJobConfig {

    public static final String JOB_NAME = "pagingSettlementJob";
    public static final String STEP_NAME = "pagingSettlementStep";
    public static final int CHUNK_SIZE = 1_000;
    public static final int PAGE_SIZE = 1_000;
    public static final int FETCH_SIZE = 1_000;

    @Bean
    @StepScope
    public JpaPagingItemReader<Settlement> pagingSettlementReader(
            EntityManagerFactory entityManagerFactory,
            @Value("${benchmark.enabled:false}") boolean benchmarkMetricsEnabled) {
        TimedJpaPagingItemReader reader =
                new TimedJpaPagingItemReader(entityManagerFactory, benchmarkMetricsEnabled);
        reader.setName("pagingSettlementReader");
        reader.setQueryString("select s from Settlement s order by s.id asc");
        reader.setHintValues(Map.of(HibernateHints.HINT_FETCH_SIZE, FETCH_SIZE));
        reader.setPageSize(PAGE_SIZE);
        reader.setSaveState(true);
        return reader;
    }

    @Bean
    public ItemWriter<Settlement> pagingSettlementWriter() {
        // 비교 실험에서 저장 I/O를 만들지 않는다. 전달된 건수는 Step의 writeCount에 기록된다.
        return chunk -> {
            // no-op
        };
    }

    @Bean
    public Step pagingSettlementStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JpaPagingItemReader<Settlement> pagingSettlementReader,
            ItemWriter<Settlement> pagingSettlementWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Settlement, Settlement>chunk(CHUNK_SIZE)
                .reader(pagingSettlementReader)
                .writer(pagingSettlementWriter)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    public Job pagingSettlementJob(JobRepository jobRepository, Step pagingSettlementStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(pagingSettlementStep)
                .build();
    }
}
