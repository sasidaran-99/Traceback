package com.traceback.core.investigation;

/**
 * Thrown when a concurrent follower thread is interrupted while awaiting
 * the result of an active leader investigation.
 *
 * Preserves thread interrupt status and prevents fallback execution on a cancelled thread.
 */
public class DeduplicationInterruptedException extends RuntimeException {

    public DeduplicationInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
