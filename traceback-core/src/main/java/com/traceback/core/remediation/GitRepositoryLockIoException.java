package com.traceback.core.remediation;

/**
 * Thrown when an underlying operating system or filesystem I/O error prevents lock acquisition or release.
 */
public class GitRepositoryLockIoException extends GitRepositoryLockException {

    public GitRepositoryLockIoException(String message, Throwable cause) {
        super(message, cause);
    }

    public GitRepositoryLockIoException(String message) {
        super(message);
    }
}
