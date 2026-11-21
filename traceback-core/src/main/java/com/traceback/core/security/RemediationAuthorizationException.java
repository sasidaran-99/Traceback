package com.traceback.core.security;

/**
 * Thrown when an actor lacks sufficient permissions or role privileges for a requested operation.
 */
public class RemediationAuthorizationException extends RuntimeException {
    public RemediationAuthorizationException(String message) {
        super(message);
    }
}
