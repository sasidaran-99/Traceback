package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationBreakGlassRequestEntity;

import java.util.Objects;

/**
 * Typed outcome of a break-glass finalization attempt.
 * Accurately conveys whether the decision was applied or whether the request was
 * durably committed into a terminal invalidation state (EXPIRED or STALE_INVALIDATED).
 * Carries authoritative locked operation status and target-ref frozen state.
 */
public record BreakGlassFinalizationOutcome(
        RemediationBreakGlassStatus status,
        RemediationBreakGlassRequestEntity request,
        String message,
        boolean success,
        RemediationOperationStatus operationStatus,
        Boolean refFrozen
) {
    public BreakGlassFinalizationOutcome {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
    }

    public static BreakGlassFinalizationOutcome executedKeptFrozen(
            RemediationBreakGlassRequestEntity request,
            RemediationOperationStatus operationStatus,
            Boolean refFrozen
    ) {
        return new BreakGlassFinalizationOutcome(
                RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN,
                request,
                "Dual-custody review confirmed target ref remains frozen in " +
                        (operationStatus != null ? operationStatus.name() : "RECOVERY_REQUIRED") +
                        ". No Git recovery was executed.",
                true,
                operationStatus,
                refFrozen
        );
    }

    public static BreakGlassFinalizationOutcome expired(
            RemediationBreakGlassRequestEntity request,
            RemediationOperationStatus operationStatus,
            Boolean refFrozen
    ) {
        return new BreakGlassFinalizationOutcome(
                RemediationBreakGlassStatus.EXPIRED,
                request,
                "Break-glass request has expired and cannot be approved.",
                false,
                operationStatus,
                refFrozen
        );
    }

    public static BreakGlassFinalizationOutcome staleInvalidated(
            RemediationBreakGlassRequestEntity request,
            String reason,
            RemediationOperationStatus operationStatus,
            Boolean refFrozen
    ) {
        return new BreakGlassFinalizationOutcome(
                RemediationBreakGlassStatus.STALE_INVALIDATED,
                request,
                "Break-glass request invalidated due to state drift: " + reason,
                false,
                operationStatus,
                refFrozen
        );
    }

    // Convenience overloads defaulting to RECOVERY_REQUIRED and true when not explicitly passed
    public static BreakGlassFinalizationOutcome executedKeptFrozen(RemediationBreakGlassRequestEntity request) {
        return executedKeptFrozen(request, RemediationOperationStatus.RECOVERY_REQUIRED, true);
    }

    public static BreakGlassFinalizationOutcome expired(RemediationBreakGlassRequestEntity request) {
        return expired(request, RemediationOperationStatus.RECOVERY_REQUIRED, true);
    }

    public static BreakGlassFinalizationOutcome staleInvalidated(RemediationBreakGlassRequestEntity request, String reason) {
        return staleInvalidated(request, reason, RemediationOperationStatus.RECOVERY_REQUIRED, true);
    }

    public boolean isExecuted() {
        return status == RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN;
    }

    public boolean isExpired() {
        return status == RemediationBreakGlassStatus.EXPIRED;
    }

    public boolean isStaleInvalidated() {
        return status == RemediationBreakGlassStatus.STALE_INVALIDATED;
    }
}
