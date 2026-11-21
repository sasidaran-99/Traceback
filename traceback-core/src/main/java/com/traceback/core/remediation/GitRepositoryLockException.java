package com.traceback.core.remediation;

/**
 * Base exception for repository-level locking failures.
 */
public class GitRepositoryLockException extends RuntimeException {

    public GitRepositoryLockException(String message) {
        super(message);
    }

    public GitRepositoryLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
