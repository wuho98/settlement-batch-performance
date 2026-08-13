package com.example.settlementbatchperformance.metrics;

import com.example.settlementbatchperformance.domain.Settlement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BenchmarkPageLog {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkPageLog.class);
    private static volatile RunContext currentRun;

    private BenchmarkPageLog() {
    }

    public static void beginRun(
            String benchmarkId, String phase, int round, int sequence, String reader) {
        currentRun = new RunContext(benchmarkId, phase, round, sequence, reader);
    }

    public static void endRun() {
        currentRun = null;
    }

    public static void record(
            boolean enabled,
            String reader,
            int page,
            String positionType,
            String positionValue,
            List<Settlement> items,
            long durationNanos) {
        if (!enabled) {
            return;
        }

        RunContext runContext = currentRun;
        if (runContext == null) {
            throw new IllegalStateException("Benchmark page logging started without run context");
        }
        if (!runContext.reader().equals(reader)) {
            throw new IllegalStateException(
                    "Benchmark reader mismatch: expected " + runContext.reader() + " but was " + reader);
        }

        String firstId = items.isEmpty() ? "" : items.getFirst().getId().toString();
        String lastId = items.isEmpty() ? "" : items.getLast().getId().toString();
        log.info(
                "BENCHMARK_PAGE|benchmarkId={}|phase={}|round={}|sequence={}|reader={}"
                        + "|page={}|positionType={}|positionValue={}"
                        + "|rows={}|firstId={}|lastId={}|durationNanos={}",
                runContext.benchmarkId(),
                runContext.phase(),
                runContext.round(),
                runContext.sequence(),
                reader,
                page,
                positionType,
                positionValue,
                items.size(),
                firstId,
                lastId,
                durationNanos);
    }

    private record RunContext(
            String benchmarkId, String phase, int round, int sequence, String reader) {
    }
}
