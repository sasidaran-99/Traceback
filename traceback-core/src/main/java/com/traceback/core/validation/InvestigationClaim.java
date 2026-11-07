package com.traceback.core.validation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable assertion produced by an investigation, backed by registered evidence.
 */
public record InvestigationClaim(
        String claimId,
        UUID investigationId,
        String statement,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds,
        ClaimValidationStatus status,
        String explanation,
        Instant assertedAt
) {
    public InvestigationClaim {
        if (supportingEvidenceIds == null) {
            supportingEvidenceIds = Collections.emptyList();
        }
        if (contradictingEvidenceIds == null) {
            contradictingEvidenceIds = Collections.emptyList();
        }
        if (assertedAt == null) {
            assertedAt = Instant.now();
        }
    }

    public static InvestigationClaim create(String claimId, UUID investigationId, String statement,
                                            List<String> supportingEvidenceIds,
                                            List<String> contradictingEvidenceIds) {
        return new InvestigationClaim(
                claimId,
                investigationId,
                statement,
                supportingEvidenceIds,
                contradictingEvidenceIds,
                ClaimValidationStatus.UNSUPPORTED,
                "Awaiting deterministic validation",
                Instant.now()
        );
    }

    public InvestigationClaim withValidation(ClaimValidationStatus newStatus, String newExplanation) {
        return new InvestigationClaim(
                this.claimId,
                this.investigationId,
                this.statement,
                this.supportingEvidenceIds,
                this.contradictingEvidenceIds,
                newStatus,
                newExplanation,
                this.assertedAt
        );
    }
}
