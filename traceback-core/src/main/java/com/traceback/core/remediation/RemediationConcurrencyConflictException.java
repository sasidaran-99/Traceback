package com.traceback.core.remediation;

public class RemediationConcurrencyConflictException extends RuntimeException {

    public RemediationConcurrencyConflictException(String message) {
        super(message);
    }

    public RemediationConcurrencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
