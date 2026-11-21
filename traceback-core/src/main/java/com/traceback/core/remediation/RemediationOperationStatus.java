package com.traceback.core.remediation;

/**
 * Lifecycle states for remediation operations.
 */
public enum RemediationOperationStatus {
    CREATED,
    VALIDATED,
    PRE_GIT_CAS_INTENT,
    GIT_CAS_SUCCEEDED,
    PERSISTED,
    RECOVERY_REQUIRED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == PERSISTED || this == FAILED || this == CANCELLED;
    }
}
