package com.traceback.core.remediation;

import java.time.Duration;

/**
 * Thrown when lock acquisition fails due to lock contention exceeding the configured timeout.
 */
public class GitRepositoryLockTimeoutException extends GitRepositoryLockException {

    private final String repositoryPath;
    private final Duration timeout;

    public GitRepositoryLockTimeoutException(String repositoryPath, Duration timeout, String message) {
        super(message);
        this.repositoryPath = repositoryPath;
        this.timeout = timeout;
    }

    public String getRepositoryPath() {
        return repositoryPath;
    }

    public Duration getTimeout() {
        return timeout;
    }
}
