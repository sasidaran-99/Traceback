package com.traceback.core.agent.bifrost;

/**
 * Exception thrown when Bifrost gateway interaction fails or encounters an error response.
 */
public class BifrostException extends RuntimeException {
    private final int statusCode;

    public BifrostException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public BifrostException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public BifrostException(String message, int statusCode) {
        super(message + " (HTTP " + statusCode + ")");
        this.statusCode = statusCode;
    }

    public BifrostException(String message, Throwable cause, int statusCode) {
        super(statusCode > 0 && !message.contains("(HTTP " + statusCode + ")") ? message + " (HTTP " + statusCode + ")" : message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
