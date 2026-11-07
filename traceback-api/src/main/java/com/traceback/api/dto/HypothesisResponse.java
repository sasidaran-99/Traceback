package com.traceback.api.dto;

import com.traceback.core.domain.Hypothesis;

import java.util.Collections;
import java.util.List;
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
        List<String> evidenceIds
) {
    public static HypothesisResponse fromEntity(Hypothesis hypothesis, List<String> evidenceIds) {
        return new HypothesisResponse(
                hypothesis.getId(),
                hypothesis.getRank(),
                hypothesis.getConfidenceScore(),
                hypothesis.getStatus().name(),
                hypothesis.getSummary(),
                hypothesis.getVerificationPlan(),
                hypothesis.getCounterEvidence(),
                evidenceIds != null ? evidenceIds : Collections.emptyList()
        );
    }
}
