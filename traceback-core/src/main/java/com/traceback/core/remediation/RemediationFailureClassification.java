package com.traceback.core.remediation;

/**
 * High-level failure classifications for safe error categorization and diagnostics.
 */
public enum RemediationFailureClassification {
    GIT_CAS_MISMATCH,
    GIT_LOCK_FAILURE,
    GIT_IO_FAILURE,
    GIT_REF_NOT_FOUND,
    STALE_FENCING_TOKEN,
    LEASE_EXPIRED,
    CONCURRENT_OPERATION_CONFLICT,
    TARGET_REF_FROZEN,
    UNAUTHORIZED,
    DATABASE_ERROR,
    SYSTEM_CRASH_OR_TIMEOUT,
    VALIDATION_FAILURE
}
