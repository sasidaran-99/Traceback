package com.traceback.core.investigation;

/**
 * Thrown when a concurrent follower request exceeds the configured in-flight wait timeout
 * while an identical leader investigation is actively executing.
 *
 * Mapped to HTTP 504 Gateway Timeout with a Retry-After header by the API layer,
 * rejecting duplicate execution without cancelling or interrupting the active leader.
 */
public class DeduplicationWaitTimeoutException extends RuntimeException {

    private final String fingerprint;
    private final long waitTimeoutSeconds;

    public DeduplicationWaitTimeoutException(String fingerprint, long waitTimeoutSeconds) {
        super(String.format("An identical investigation is currently in progress for this request (fingerprint: %s). " +
                "Timed out waiting after %d seconds; please retry shortly.", fingerprint, waitTimeoutSeconds));
        this.fingerprint = fingerprint;
        this.waitTimeoutSeconds = waitTimeoutSeconds;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public long getWaitTimeoutSeconds() {
        return waitTimeoutSeconds;
    }
}
