package com.traceback.api.dto;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;

import java.time.Instant;
import java.util.UUID;

/**
 * High-level investigation overview returned to the client.
 */
public record InvestigationResponse(
        UUID investigationId,
        InvestigationMode investigationMode,
        String serviceName,
        String environment,
        String status,
        int commitsAnalyzed,
        int errorsDetected,
        int hypothesesCount,
        int claimsCount,
        boolean evidenceSufficient,
        HypothesisResponse topHypothesis,
        Instant startedAt,
        Instant completedAt,
        Boolean persistedDurable,
        String persistenceStatus
) {
    public InvestigationResponse(
            UUID investigationId,
            InvestigationMode investigationMode,
            String serviceName,
            String environment,
            String status,
            int commitsAnalyzed,
            int errorsDetected,
            int hypothesesCount,
            int claimsCount,
            boolean evidenceSufficient,
            HypothesisResponse topHypothesis,
            Instant startedAt,
            Instant completedAt
    ) {
        this(investigationId, investigationMode, serviceName, environment, status,
                commitsAnalyzed, errorsDetected, hypothesesCount, claimsCount, evidenceSufficient,
                topHypothesis, startedAt, completedAt, true, "DURABLE");
    }

    public InvestigationResponse(
            UUID investigationId,
            String serviceName,
            String environment,
            String status,
            int commitsAnalyzed,
            int errorsDetected,
            int hypothesesCount,
            int claimsCount,
            boolean evidenceSufficient,
            HypothesisResponse topHypothesis,
            Instant startedAt,
            Instant completedAt
    ) {
        this(investigationId, InvestigationMode.RUNTIME_FAILURE, serviceName, environment, status,
                commitsAnalyzed, errorsDetected, hypothesesCount, claimsCount, evidenceSufficient,
                topHypothesis, startedAt, completedAt, true, "DURABLE");
    }

    public static InvestigationResponse fromResult(InvestigationResult result) {
        HypothesisResponse top = null;
        if (!result.hypotheses().isEmpty()) {
            Hypothesis h = result.hypotheses().get(0);
            top = HypothesisResponse.fromEntity(h, h.getSupportingEvidenceIds());
        }

        int hypCount = result.hypotheses().size();
        if (hypCount == 0 && result.commitsAnalyzed() > 0) {
            hypCount = result.commitsAnalyzed();
        }

        return new InvestigationResponse(
                result.investigationId(),
                result.investigationMode(),
                result.serviceName(),
                result.environment(),
                result.status(),
                result.commitsAnalyzed(),
                result.errorsDetected(),
                hypCount,
                result.claims().size(),
                result.evidenceSufficient(),
                top,
                result.startedAt(),
                result.completedAt(),
                result.persistedDurable(),
                result.persistenceStatus()
        );
    }
}
