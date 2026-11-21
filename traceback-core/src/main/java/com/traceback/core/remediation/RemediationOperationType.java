package com.traceback.core.remediation;

/**
 * Type of remediation operation supported by TraceBack 2.0.
 */
public enum RemediationOperationType {
    BRANCH_PATCH_PROMOTION,
    STAGING_ROLLBACK,
    QUARANTINE_ISOLATION
}
