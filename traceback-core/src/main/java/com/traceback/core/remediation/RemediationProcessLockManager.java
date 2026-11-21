package com.traceback.core.remediation;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages in-process per-target-ref execution locks to prevent concurrent worker execution
 * on the same Git ref within a single process.
 */
@Component
public class RemediationProcessLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> refExecutionLocks = new ConcurrentHashMap<>();

    public String getLockKey(String repositoryPath, String targetRef) {
        String normalizedRepo;
        try {
            normalizedRepo = Path.of(repositoryPath).toRealPath().normalize().toString();
        } catch (Exception e) {
            normalizedRepo = Path.of(repositoryPath).toAbsolutePath().normalize().toString();
        }
        return normalizedRepo + "#" + targetRef;
    }

    public ReentrantLock getLock(String repositoryPath, String targetRef) {
        return refExecutionLocks.computeIfAbsent(getLockKey(repositoryPath, targetRef), k -> new ReentrantLock());
    }

    public boolean isLocked(String repositoryPath, String targetRef) {
        ReentrantLock lock = refExecutionLocks.get(getLockKey(repositoryPath, targetRef));
        return lock != null && lock.isLocked();
    }
}
