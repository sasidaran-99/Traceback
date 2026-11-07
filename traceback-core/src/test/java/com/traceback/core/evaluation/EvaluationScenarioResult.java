package com.traceback.core.evaluation;

import com.traceback.core.domain.Hypothesis;

/**
 * Result of evaluating a single incident scenario.
 */
public record EvaluationScenarioResult(
        String scenarioId,
        String title,
        boolean top1Matched,
        boolean top3Matched,
        boolean abstainedCorrectly,
        int totalRelationships,
        int falsePositiveRelationships,
        long executionTimeMs,
        Hypothesis topHypothesis
) {}
