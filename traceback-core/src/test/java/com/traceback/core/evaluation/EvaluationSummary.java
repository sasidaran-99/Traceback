package com.traceback.core.evaluation;

import java.util.List;

/**
 * Aggregated benchmark summary computed over all evaluated scenarios.
 */
public record EvaluationSummary(
        int totalScenarios,
        int causalScenarios,
        int insufficientScenarios,
        double top1Accuracy,
        double top3Accuracy,
        double falsePositiveRelationshipRate,
        double correctAbstentionRate,
        long totalExecutionTimeMs,
        double averageExecutionTimeMs,
        List<EvaluationScenarioResult> scenarioResults
) {
    public String toReportString() {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================================================================\n");
        sb.append("                        TRACEBACK 2.0 EVALUATION HARNESS BENCHMARK REPORT               \n");
        sb.append("========================================================================================\n\n");
        sb.append(String.format("Total Scenarios Evaluated  : %d (%d causal, %d insufficient-evidence)\n", totalScenarios, causalScenarios, insufficientScenarios));
        sb.append(String.format("Top-1 Root-Cause Accuracy  : %.1f%%\n", top1Accuracy * 100));
        sb.append(String.format("Top-3 Root-Cause Accuracy  : %.1f%%\n", top3Accuracy * 100));
        sb.append(String.format("False-Positive Rate (FPRR) : %.1f%%\n", falsePositiveRelationshipRate * 100));
        sb.append(String.format("Correct Abstention Rate    : %.1f%%\n", correctAbstentionRate * 100));
        sb.append(String.format("Total Benchmark Latency    : %d ms (avg: %.1f ms/scenario)\n\n", totalExecutionTimeMs, averageExecutionTimeMs));
        sb.append("----------------------------------------------------------------------------------------\n");
        sb.append(String.format("%-18s | %-32s | %-6s | %-6s | %-8s | %-7s\n", "Scenario ID", "Title", "Top-1", "Top-3", "Abstain", "Time"));
        sb.append("----------------------------------------------------------------------------------------\n");
        for (EvaluationScenarioResult res : scenarioResults) {
            sb.append(String.format("%-18s | %-32s | %-6s | %-6s | %-8s | %d ms\n",
                    res.scenarioId(),
                    res.title().length() > 32 ? res.title().substring(0, 29) + "..." : res.title(),
                    res.top1Matched() ? "PASS" : (res.abstainedCorrectly() ? "N/A" : "FAIL"),
                    res.top3Matched() ? "PASS" : (res.abstainedCorrectly() ? "N/A" : "FAIL"),
                    res.abstainedCorrectly() ? "YES" : "NO",
                    res.executionTimeMs()
            ));
        }
        sb.append("========================================================================================\n");
        return sb.toString();
    }
}
