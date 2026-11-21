package com.traceback.core.remediation;

public class StaleFencingTokenException extends RuntimeException {

    private final Long providedToken;
    private final Long currentToken;

    public StaleFencingTokenException(Long providedToken, Long currentToken) {
        super(String.format("Fencing token mismatch: provided=%d, current=%d. Worker execution fenced.", providedToken, currentToken));
        this.providedToken = providedToken;
        this.currentToken = currentToken;
    }

    public Long getProvidedToken() {
        return providedToken;
    }

    public Long getCurrentToken() {
        return currentToken;
    }
}
