package com.example.settlementbatchperformance.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkRunnerTest {

    @Test
    void separatesWarmupAndCrossesTheThreeMeasuredRounds() {
        List<BenchmarkRunner.RunSpec> plan = BenchmarkRunner.runPlan();

        assertThat(plan).hasSize(8);
        assertThat(plan.subList(0, 2))
                .extracting(BenchmarkRunner.RunSpec::phase)
                .containsOnly(BenchmarkRunner.Phase.WARMUP);
        assertThat(plan.subList(2, 8))
                .extracting(BenchmarkRunner.RunSpec::phase)
                .containsOnly(BenchmarkRunner.Phase.MEASURED);
        assertThat(plan.subList(2, 4))
                .extracting(BenchmarkRunner.RunSpec::readerType)
                .containsExactly(
                        BenchmarkRunner.ReaderType.PAGING,
                        BenchmarkRunner.ReaderType.ZERO_OFFSET);
        assertThat(plan.subList(4, 6))
                .extracting(BenchmarkRunner.RunSpec::readerType)
                .containsExactly(
                        BenchmarkRunner.ReaderType.ZERO_OFFSET,
                        BenchmarkRunner.ReaderType.PAGING);
        assertThat(plan.subList(6, 8))
                .extracting(BenchmarkRunner.RunSpec::readerType)
                .containsExactly(
                        BenchmarkRunner.ReaderType.PAGING,
                        BenchmarkRunner.ReaderType.ZERO_OFFSET);
    }
}
