package com.traceback.api.dto;

import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.Hypothesis;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detailed representation of a candidate root-cause hypothesis.
 */
public record HypothesisResponse(
        UUID id,
        int rank,
        double confidenceScore,
        String status,
        String summary,
        String verificationPlan,
        String counterEvidence,
        List<String> evidenceIds,
        List<String> contradictingEvidenceIds,
        List<String> matchedRules,
        Map<String, String> ruleMatchReasons,
        List<String> penaltyReasons,
        String rankingExplanation,
        ConfidenceBreakdown confidenceBreakdown
) {
    public static HypothesisResponse fromEntity(Hypothesis hypothesis, List<String> evidenceIds) {
        List<String> supporting = evidenceIds != null && !evidenceIds.isEmpty()
                ? evidenceIds
                : hypothesis.getSupportingEvidenceIds();

        return new HypothesisResponse(
                hypothesis.getId(),
                hypothesis.getRank(),
                hypothesis.getConfidenceScore(),
                hypothesis.getStatus().name(),
                hypothesis.getSummary(),
                hypothesis.getVerificationPlan(),
                hypothesis.getCounterEvidence(),
                supporting != null ? supporting : Collections.emptyList(),
                hypothesis.getContradictingEvidenceIds() != null ? hypothesis.getContradictingEvidenceIds() : Collections.emptyList(),
                hypothesis.getMatchedRules() != null ? hypothesis.getMatchedRules() : Collections.emptyList(),
                hypothesis.getRuleMatchReasons() != null ? hypothesis.getRuleMatchReasons() : Collections.emptyMap(),
                hypothesis.getPenaltyReasons() != null ? hypothesis.getPenaltyReasons() : Collections.emptyList(),
                hypothesis.getRankingExplanation(),
                hypothesis.getConfidenceBreakdown()
        );
    }
}
