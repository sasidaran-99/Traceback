package com.traceback.core.remediation;

import java.nio.file.Path;

/**
 * Safe handle representing an acquired repository lock, for use in try-with-resources.
 * Guarantees release of both the operating-system file lock and the in-process JVM lock upon close.
 */
public interface AutoCloseableLock extends AutoCloseable {

    @Override
    void close();

    Path getRepositoryPath();

    Path getLockFilePath();

    boolean isValid();
}
