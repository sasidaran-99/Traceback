package com.traceback.core.security;

/**
 * Thrown when an unauthenticated actor attempts an operation requiring authentication.
 */
public class RemediationAuthenticationException extends RuntimeException {
    public RemediationAuthenticationException(String message) {
        super(message);
    }
}
