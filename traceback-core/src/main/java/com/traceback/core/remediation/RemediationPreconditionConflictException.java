package com.traceback.core.remediation;

/**
 * Thrown when a remediation break-glass operation cannot proceed because the
 * target operation or reference is not in the required state (e.g. operation is not
 * in RECOVERY_REQUIRED or target ref is not frozen).
 * Extends IllegalStateException for backward compatibility with existing unit test assertions.
 */
public class RemediationPreconditionConflictException extends IllegalStateException {
    public RemediationPreconditionConflictException(String message) {
        super(message);
    }
}
