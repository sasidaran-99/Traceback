package com.traceback.core.correlation;

import java.util.Collections;
import java.util.List;

/**
 * Detailed, transparent breakdown of how a hypothesis confidence score was computed.
 * Follows the deterministic scoring formula: S = T + O + A - C, normalized to [0.0, 1.0].
 */
public record ConfidenceBreakdown(
        double temporalPoints,
        double overlapPoints,
        double agreementPoints,
        double contradictionPenalty,
        double rawScore,
        double normalizedScore,
        List<String> scoreReasons,
        List<String> penaltyReasons
) {
    public ConfidenceBreakdown {
        if (scoreReasons == null) {
            scoreReasons = Collections.emptyList();
        }
        if (penaltyReasons == null) {
            penaltyReasons = Collections.emptyList();
        }
    }

    public static ConfidenceBreakdown calculate(
            double temporalPoints,
            double overlapPoints,
            double agreementPoints,
            double contradictionPenalty,
            List<String> scoreReasons,
            List<String> penaltyReasons
    ) {
        // Enforce maximum positive caps
        double t = Math.max(0.0, Math.min(30.0, temporalPoints));
        double o = Math.max(0.0, Math.min(30.0, overlapPoints));
        double a = Math.max(0.0, Math.min(25.0, agreementPoints));
        double c = Math.max(0.0, Math.min(30.0, contradictionPenalty));

        // Raw score: S = T + O + A - C
        double raw = t + o + a - c;

        // Theoretical maximum positive points is 30 + 30 + 25 = 85
        double normalized = Math.max(0.0, raw) / 85.0;
        normalized = Math.round(normalized * 100.0) / 100.0;
        normalized = Math.min(1.0, Math.max(0.0, normalized));

        return new ConfidenceBreakdown(t, o, a, c, raw, normalized, scoreReasons, penaltyReasons);
    }

    public String toExplanation() {
        return String.format(
                "Confidence: %.0f%% (Score: %.1f/85.0 | Temporal: +%.0f, Overlap: +%.0f, Agreement: +%.0f, Penalty: -%.0f)",
                normalizedScore * 100, Math.max(0.0, rawScore), temporalPoints, overlapPoints, agreementPoints, contradictionPenalty
        );
    }
}
