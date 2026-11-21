package com.traceback.core.remediation;

/**
 * Thrown when an illegal state transition is attempted on a remediation operation.
 */
public class RemediationInvalidStateTransitionException extends RuntimeException {

    private final RemediationOperationStatus fromStatus;
    private final RemediationOperationStatus toStatus;

    public RemediationInvalidStateTransitionException(RemediationOperationStatus fromStatus, RemediationOperationStatus toStatus) {
        super(String.format("Invalid remediation operation transition from '%s' to '%s'", fromStatus, toStatus));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public RemediationOperationStatus getFromStatus() {
        return fromStatus;
    }

    public RemediationOperationStatus getToStatus() {
        return toStatus;
    }
}
