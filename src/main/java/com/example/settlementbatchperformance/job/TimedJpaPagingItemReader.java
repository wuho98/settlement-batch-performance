package com.example.settlementbatchperformance.job;

import com.example.settlementbatchperformance.domain.Settlement;
import com.example.settlementbatchperformance.metrics.BenchmarkPageLog;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;

public class TimedJpaPagingItemReader extends JpaPagingItemReader<Settlement> {

    private final boolean benchmarkMetricsEnabled;

    public TimedJpaPagingItemReader(
            EntityManagerFactory entityManagerFactory, boolean benchmarkMetricsEnabled) {
        super(entityManagerFactory);
        this.benchmarkMetricsEnabled = benchmarkMetricsEnabled;
    }

    @Override
    protected void doReadPage() {
        int pageNumber = getPage();
        long startedAt = System.nanoTime();

        super.doReadPage();

        long durationNanos = System.nanoTime() - startedAt;
        List<Settlement> pageItems = results == null ? List.of() : List.copyOf(results);
        BenchmarkPageLog.record(
                benchmarkMetricsEnabled,
                "PAGING",
                pageNumber,
                "OFFSET",
                Long.toString((long) pageNumber * getPageSize()),
                pageItems,
                durationNanos);
    }
}
