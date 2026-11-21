package com.traceback.api.dto;

import com.traceback.core.remediation.BreakGlassFinalizationOutcome;
import com.traceback.core.remediation.RemediationBreakGlassStatus;

import java.util.Objects;

/**
 * Public response DTO for a break-glass finalization action.
 * Explicitly distinguishes decision recording from Git recovery execution,
 * ensuring that KEEP_FROZEN unambiguously reports that Git recovery was NOT executed.
 * Reflects authoritative operation status and ref-frozen state populated from locked entities.
 */
public record BreakGlassFinalizationResponse(
        boolean decisionApplied,
        boolean gitRecoveryExecuted,
        Boolean refFrozen,
        String operationStatus,
        RemediationBreakGlassStatus status,
        String message,
        RemediationBreakGlassResponse request
) {
    public static BreakGlassFinalizationResponse fromOutcome(BreakGlassFinalizationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome cannot be null");
        boolean decisionApplied = outcome.success();
        boolean gitRecoveryExecuted = false; // Always false: KEEP_FROZEN never performs Git recovery

        // Read authoritative values directly from outcome; preserve null when state cannot be verified
        Boolean refFrozen = outcome.refFrozen();
        String operationStatus = outcome.operationStatus() != null ? outcome.operationStatus().name() : "UNKNOWN";

        return new BreakGlassFinalizationResponse(
                decisionApplied,
                gitRecoveryExecuted,
                refFrozen,
                operationStatus,
                outcome.status(),
                outcome.message(),
                RemediationBreakGlassResponse.fromEntity(outcome.request())
        );
    }
}
