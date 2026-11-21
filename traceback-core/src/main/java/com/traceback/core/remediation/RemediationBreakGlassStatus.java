package com.traceback.core.remediation;

/**
 * Lifecycle states for dual-custody break-glass recovery requests.
 */
public enum RemediationBreakGlassStatus {
    PENDING_SECOND_APPROVAL,
    EXECUTED_KEPT_FROZEN,
    REJECTED,
    EXPIRED,
    CANCELLED,
    STALE_INVALIDATED;

    /**
     * Checks if this request status represents an irreversible terminal state.
     */
    public boolean isTerminal() {
        return this != PENDING_SECOND_APPROVAL;
    }
}
