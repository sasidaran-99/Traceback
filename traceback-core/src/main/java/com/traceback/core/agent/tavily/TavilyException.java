package com.traceback.core.agent.tavily;

/**
 * Exception thrown when Tavily API calls fail or return an error response.
 */
public class TavilyException extends RuntimeException {
    private final int statusCode;

    public TavilyException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public TavilyException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public TavilyException(String message, int statusCode) {
        super(message + " (HTTP " + statusCode + ")");
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
