package com.traceback.api.dto;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.investigation.InvestigationResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * High-level investigation overview returned to the client.
 */
public record InvestigationResponse(
        UUID investigationId,
        String serviceName,
        String environment,
        String status,
        int commitsAnalyzed,
        int errorsDetected,
        int hypothesesCount,
        HypothesisResponse topHypothesis,
        Instant startedAt,
        Instant completedAt
) {
    public static InvestigationResponse fromResult(InvestigationResult result) {
        HypothesisResponse top = null;
        if (!result.hypotheses().isEmpty()) {
            Hypothesis h = result.hypotheses().get(0);
            top = HypothesisResponse.fromEntity(h, Collections.emptyList());
        }

        return new InvestigationResponse(
                result.investigationId(),
                result.serviceName(),
                result.environment(),
                result.status(),
                result.commitsAnalyzed(),
                result.errorsDetected(),
                result.hypotheses().size(),
                top,
                result.startedAt(),
                result.completedAt()
        );
    }
}
