package com.traceback.core.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationHarnessTest {

    @Test
    @DisplayName("Evaluation Harness: Run benchmark over all 5 incident scenarios and assert benchmark thresholds")
    void shouldExecuteEvaluationHarnessBenchmarkSuccessfully() {
        EvaluationHarness harness = new EvaluationHarness();

        EvaluationSummary summary = harness.runStandardBenchmark();

        // Print readable evaluation report to stdout
        System.out.println(summary.toReportString());

        // 1. Total scenarios evaluated
        assertThat(summary.totalScenarios()).isEqualTo(5);
        assertThat(summary.causalScenarios()).isEqualTo(3);
        assertThat(summary.insufficientScenarios()).isEqualTo(2);

        // 2. Accuracy metrics on causal scenarios (Scenarios A, B, D)
        assertThat(summary.top1Accuracy()).isEqualTo(1.0); // 100% Top-1 Accuracy
        assertThat(summary.top3Accuracy()).isEqualTo(1.0); // 100% Top-3 Accuracy

        // 3. Precision & noise suppression
        assertThat(summary.falsePositiveRelationshipRate()).isLessThanOrEqualTo(0.05); // <= 5% false positive rate

        // 4. Honest abstention on insufficient scenarios (Scenarios C, E)
        assertThat(summary.correctAbstentionRate()).isEqualTo(1.0); // 100% Correct Abstention

        // 5. Execution speed
        assertThat(summary.averageExecutionTimeMs()).isLessThan(500.0); // Sub-second investigation latency
    }
}
