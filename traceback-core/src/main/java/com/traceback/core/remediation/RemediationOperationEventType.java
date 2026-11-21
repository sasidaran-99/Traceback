package com.traceback.core.remediation;

/**
 * Append-only event types for the remediation journal.
 */
public enum RemediationOperationEventType {
    CREATED,
    VALIDATED,
    PRE_GIT_CAS_INTENT,
    GIT_CAS_SUCCEEDED,
    GIT_CAS_FAILED,
    PERSISTED,
    RECOVERY_INITIATED,
    RECOVERY_COMPLETED,
    OPERATION_FAILED,
    OPERATION_CANCELLED,

    // Dual-custody break-glass recovery event types
    BREAK_GLASS_PROPOSED,
    BREAK_GLASS_FREEZE_RETAINED,
    BREAK_GLASS_REJECTED,
    BREAK_GLASS_CANCELLED,
    BREAK_GLASS_EXPIRED,
    BREAK_GLASS_INVALIDATED
}
