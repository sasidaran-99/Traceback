package com.traceback.core.remediation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Abstraction for coordinating exclusive per-repository mutations and recovery across
 * JVM threads and independent operating-system processes on a single host.
 */
public interface GitRepositoryLockManager {

    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Acquires an exclusive repository lock using the default timeout (10 seconds).
     *
     * @param repositoryPath the repository path to lock
     * @return an AutoCloseableLock handle that releases both OS and JVM locks on close
     * @throws GitRepositoryLockTimeoutException if lock contention exceeds the default timeout
     * @throws GitRepositoryLockIoException      if an I/O error occurs
     * @throws GitRepositoryLockInvalidPathException if the path cannot be resolved canonically
     */
    AutoCloseableLock acquireLock(String repositoryPath);

    /**
     * Acquires an exclusive repository lock with a specified acquisition timeout.
     *
     * @param repositoryPath the repository path to lock
     * @param timeout        maximum duration to wait for lock acquisition
     * @return an AutoCloseableLock handle
     * @throws GitRepositoryLockTimeoutException if lock contention exceeds the timeout
     * @throws GitRepositoryLockIoException      if an I/O error occurs
     * @throws GitRepositoryLockInvalidPathException if the path cannot be resolved canonically
     */
    AutoCloseableLock acquireLock(String repositoryPath, Duration timeout);

    /**
     * Attempts to acquire the exclusive repository lock immediately (non-blocking).
     *
     * @param repositoryPath the repository path to lock
     * @return Optional containing the lock handle if acquired, or Optional.empty() if contended
     * @throws GitRepositoryLockIoException          if an I/O error occurs
     * @throws GitRepositoryLockInvalidPathException if the path cannot be resolved canonically
     */
    Optional<AutoCloseableLock> tryAcquireLock(String repositoryPath);

    /**
     * Checks whether the calling thread currently holds the repository lock.
     *
     * @param repositoryPath the repository path to inspect
     * @return true if the current thread holds the lock, false otherwise
     */
    boolean isLockedByCurrentThread(String repositoryPath);

    /**
     * Checks whether the repository lock is currently held by any thread or process.
     *
     * @param repositoryPath the repository path to inspect
     * @return true if held, false otherwise
     */
    boolean isLocked(String repositoryPath);

    /**
     * Returns the stable application-managed directory where lock files are stored.
     */
    Path getLockDirectory();

    /**
     * Returns the canonical lock file path corresponding to the given repository path.
     */
    Path getLockFilePath(String repositoryPath);
}
