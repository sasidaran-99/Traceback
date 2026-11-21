package com.traceback.core.remediation;

/**
 * Thrown when a supplied repository path is null, blank, non-existent, or invalid.
 */
public class GitRepositoryLockInvalidPathException extends GitRepositoryLockException {

    public GitRepositoryLockInvalidPathException(String message) {
        super(message);
    }

    public GitRepositoryLockInvalidPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
