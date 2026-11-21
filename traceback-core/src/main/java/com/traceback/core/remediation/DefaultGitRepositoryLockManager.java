package com.traceback.core.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default implementation of {@link GitRepositoryLockManager} providing two-tier locking:
 * 1. JVM-level ReentrantLock coordination preventing thread races and OverlappingFileLockException within the process.
 * 2. Operating-system level FileChannel/FileLock on a stable external lock directory preventing cross-process races.
 *
 * Designed exclusively for a single machine with local SSD/NVMe storage.
 */
@Component
public class DefaultGitRepositoryLockManager implements GitRepositoryLockManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultGitRepositoryLockManager.class);
    private static final long POLL_INTERVAL_MS = 25L;

    private final Path lockDirectory;
    private final Duration defaultTimeout;
    private final ConcurrentHashMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> lockOwners = new ConcurrentHashMap<>();

    @Autowired
    public DefaultGitRepositoryLockManager(
            @Value("${traceback.git.lock-directory:#{null}}") String configuredLockDir,
            @Autowired(required = false) Environment env
    ) {
        this(
                configuredLockDir,
                DEFAULT_TIMEOUT,
                GitLockDirectorySafetyValidator.isProduction(env)
        );
    }

    public DefaultGitRepositoryLockManager() {
        this(
                (String) null,
                DEFAULT_TIMEOUT,
                GitLockDirectorySafetyValidator.isProduction(null)
        );
    }

    public DefaultGitRepositoryLockManager(@Value("${traceback.git.lock-directory:#{null}}") String configuredLockDir) {
        this(
                configuredLockDir,
                DEFAULT_TIMEOUT,
                GitLockDirectorySafetyValidator.isProduction(null)
        );
    }

    public DefaultGitRepositoryLockManager(String configuredLockDir, boolean isProd) {
        this(
                configuredLockDir,
                DEFAULT_TIMEOUT,
                isProd
        );
    }

    public DefaultGitRepositoryLockManager(String configuredLockDir, Duration defaultTimeout, boolean isProd) {
        this(
                GitLockDirectorySafetyValidator.resolveLockDirectory(configuredLockDir, isProd),
                defaultTimeout,
                isProd
        );
    }

    public DefaultGitRepositoryLockManager(String configuredLockDir, Duration defaultTimeout, boolean isProd, Path systemTmpDir) {
        this(
                GitLockDirectorySafetyValidator.resolveLockDirectory(configuredLockDir, isProd, systemTmpDir),
                defaultTimeout,
                isProd
        );
    }

    public DefaultGitRepositoryLockManager(Path lockDirectory) {
        this(lockDirectory, DEFAULT_TIMEOUT, false);
    }

    public DefaultGitRepositoryLockManager(Path lockDirectory, Duration defaultTimeout) {
        this(lockDirectory, defaultTimeout, false);
    }

    public DefaultGitRepositoryLockManager(Path lockDirectory, Duration defaultTimeout, boolean isProd) {
        this.lockDirectory = Objects.requireNonNull(lockDirectory, "lockDirectory cannot be null").toAbsolutePath().normalize();
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout cannot be null");
        initializeLockDirectory(isProd);
    }

    private void initializeLockDirectory(boolean isProd) {
        try {
            Files.createDirectories(lockDirectory);
            if (isProd) {
                GitLockDirectorySafetyValidator.verifyDirectoryWritabilityAndProbe(lockDirectory);
            }
            log.info("Initialized GitRepositoryLockManager with lock directory: {} (isProd={})", lockDirectory, isProd);
        } catch (IOException e) {
            throw new GitRepositoryLockIoException("Failed to create lock directory: " + lockDirectory, e);
        }
    }

    @Override
    public AutoCloseableLock acquireLock(String repositoryPath) {
        return acquireLock(repositoryPath, defaultTimeout);
    }

    @Override
    public AutoCloseableLock acquireLock(String repositoryPath, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        Path canonicalRepo = resolveCanonicalRepositoryPath(repositoryPath);
        String canonicalKey = canonicalRepo.toString();
        Path lockFilePath = getLockFilePathForCanonical(canonicalRepo);

        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(canonicalKey, k -> new ReentrantLock());

        long startNanos = System.nanoTime();
        long timeoutNanos = timeout.toNanos();

        // Tier 1: JVM-level lock acquisition
        boolean jvmAcquired;
        try {
            jvmAcquired = jvmLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitRepositoryLockException("Interrupted waiting for JVM repository lock on: " + repositoryPath, e);
        }

        if (!jvmAcquired) {
            throw new GitRepositoryLockTimeoutException(
                    repositoryPath, timeout,
                    "Timed out waiting " + timeout.toMillis() + "ms for in-process JVM lock on repository: " + repositoryPath
            );
        }

        // Tier 2: Operating-system level FileLock acquisition
        long elapsedNanos = System.nanoTime() - startNanos;
        long remainingNanos = Math.max(0, timeoutNanos - elapsedNanos);

        FileChannel channel = null;
        FileLock osLock = null;
        try {
            Files.createDirectories(lockDirectory);
            channel = FileChannel.open(
                    lockFilePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            );

            long osStartNanos = System.nanoTime();
            while (true) {
                try {
                    osLock = channel.tryLock();
                    if (osLock != null) {
                        break;
                    }
                } catch (OverlappingFileLockException e) {
                    // Handled defensively
                }

                long osElapsedNanos = System.nanoTime() - osStartNanos;
                if (osElapsedNanos >= remainingNanos) {
                    break;
                }

                long sleepMs = Math.min(POLL_INTERVAL_MS, (remainingNanos - osElapsedNanos) / 1_000_000L);
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                } else {
                    break;
                }
            }

            if (osLock == null) {
                // One final non-blocking check
                try {
                    osLock = channel.tryLock();
                } catch (Exception ignored) {
                }
            }

            if (osLock == null) {
                channel.close();
                jvmLock.unlock();
                throw new GitRepositoryLockTimeoutException(
                        repositoryPath, timeout,
                        "Timed out waiting " + timeout.toMillis() + "ms for cross-process OS file lock on: " + lockFilePath
                );
            }

            lockOwners.put(canonicalKey, Thread.currentThread());

            final FileLock lockedOsLock = osLock;
            final FileChannel lockedChannel = channel;

            return new AutoCloseableLock() {
                private final AtomicBoolean closed = new AtomicBoolean(false);

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        try {
                            if (lockedOsLock.isValid()) {
                                lockedOsLock.release();
                            }
                        } catch (IOException e) {
                            log.warn("Error releasing OS file lock on: {}", lockFilePath, e);
                        } finally {
                            try {
                                lockedChannel.close();
                            } catch (IOException e) {
                                log.warn("Error closing lock FileChannel on: {}", lockFilePath, e);
                            } finally {
                                lockOwners.remove(canonicalKey, Thread.currentThread());
                                jvmLock.unlock();
                            }
                        }
                    }
                }

                @Override
                public Path getRepositoryPath() {
                    return canonicalRepo;
                }

                @Override
                public Path getLockFilePath() {
                    return lockFilePath;
                }

                @Override
                public boolean isValid() {
                    return !closed.get() && lockedOsLock.isValid();
                }
            };

        } catch (InterruptedException e) {
            cleanupFailedAcquisition(channel, osLock, jvmLock);
            Thread.currentThread().interrupt();
            throw new GitRepositoryLockException("Interrupted waiting for OS file lock on: " + lockFilePath, e);
        } catch (IOException e) {
            cleanupFailedAcquisition(channel, osLock, jvmLock);
            throw new GitRepositoryLockIoException("I/O error during lock acquisition on: " + lockFilePath, e);
        } catch (Exception e) {
            cleanupFailedAcquisition(channel, osLock, jvmLock);
            throw e;
        }
    }

    private void cleanupFailedAcquisition(FileChannel channel, FileLock osLock, ReentrantLock jvmLock) {
        if (osLock != null) {
            try {
                osLock.release();
            } catch (Exception ignored) {
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
            }
        }
        if (jvmLock.isHeldByCurrentThread()) {
            jvmLock.unlock();
        }
    }

    @Override
    public Optional<AutoCloseableLock> tryAcquireLock(String repositoryPath) {
        Path canonicalRepo = resolveCanonicalRepositoryPath(repositoryPath);
        String canonicalKey = canonicalRepo.toString();
        Path lockFilePath = getLockFilePathForCanonical(canonicalRepo);

        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(canonicalKey, k -> new ReentrantLock());
        if (!jvmLock.tryLock()) {
            return Optional.empty();
        }

        FileChannel channel = null;
        FileLock osLock = null;
        try {
            Files.createDirectories(lockDirectory);
            channel = FileChannel.open(
                    lockFilePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            );

            osLock = channel.tryLock();
            if (osLock == null) {
                channel.close();
                jvmLock.unlock();
                return Optional.empty();
            }

            lockOwners.put(canonicalKey, Thread.currentThread());

            final FileLock lockedOsLock = osLock;
            final FileChannel lockedChannel = channel;

            return Optional.of(new AutoCloseableLock() {
                private final AtomicBoolean closed = new AtomicBoolean(false);

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        try {
                            if (lockedOsLock.isValid()) {
                                lockedOsLock.release();
                            }
                        } catch (IOException e) {
                            log.warn("Error releasing OS file lock on: {}", lockFilePath, e);
                        } finally {
                            try {
                                lockedChannel.close();
                            } catch (IOException e) {
                                log.warn("Error closing lock FileChannel on: {}", lockFilePath, e);
                            } finally {
                                lockOwners.remove(canonicalKey, Thread.currentThread());
                                jvmLock.unlock();
                            }
                        }
                    }
                }

                @Override
                public Path getRepositoryPath() {
                    return canonicalRepo;
                }

                @Override
                public Path getLockFilePath() {
                    return lockFilePath;
                }

                @Override
                public boolean isValid() {
                    return !closed.get() && lockedOsLock.isValid();
                }
            });

        } catch (IOException e) {
            cleanupFailedAcquisition(channel, osLock, jvmLock);
            throw new GitRepositoryLockIoException("I/O error during non-blocking tryAcquireLock on: " + lockFilePath, e);
        } catch (Exception e) {
            cleanupFailedAcquisition(channel, osLock, jvmLock);
            throw e;
        }
    }

    @Override
    public boolean isLockedByCurrentThread(String repositoryPath) {
        try {
            Path canonical = resolveCanonicalRepositoryPath(repositoryPath);
            Thread owner = lockOwners.get(canonical.toString());
            return owner != null && owner.equals(Thread.currentThread());
        } catch (GitRepositoryLockException e) {
            return false;
        }
    }

    @Override
    public boolean isLocked(String repositoryPath) {
        Path canonical;
        try {
            canonical = resolveCanonicalRepositoryPath(repositoryPath);
        } catch (GitRepositoryLockException e) {
            return false;
        }

        if (lockOwners.containsKey(canonical.toString())) {
            return true;
        }

        // Check if another process holds the OS file lock
        Optional<AutoCloseableLock> testLock = tryAcquireLock(repositoryPath);
        if (testLock.isPresent()) {
            testLock.get().close();
            return false;
        } else {
            return true;
        }
    }

    @Override
    public Path getLockDirectory() {
        return lockDirectory;
    }

    @Override
    public Path getLockFilePath(String repositoryPath) {
        Path canonical = resolveCanonicalRepositoryPath(repositoryPath);
        return getLockFilePathForCanonical(canonical);
    }

    private Path getLockFilePathForCanonical(Path canonicalRepo) {
        String canonicalStr = canonicalRepo.toString();
        String hash = sha256Hex(canonicalStr);
        String rawName = canonicalRepo.getFileName() != null ? canonicalRepo.getFileName().toString() : "root";
        String safeName = rawName.replaceAll("[^a-zA-Z0-9.-]", "_");
        String lockFileName = "repo_" + safeName + "_" + hash.substring(0, 16) + ".lock";
        return lockDirectory.resolve(lockFileName);
    }

    private Path resolveCanonicalRepositoryPath(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.trim().isEmpty()) {
            throw new GitRepositoryLockInvalidPathException("Repository path cannot be null or blank");
        }
        try {
            Path path = Path.of(repositoryPath);
            if (!Files.exists(path)) {
                throw new GitRepositoryLockInvalidPathException("Repository path does not exist: " + repositoryPath);
            }
            if (!Files.isDirectory(path)) {
                throw new GitRepositoryLockInvalidPathException("Repository path is not a directory: " + repositoryPath);
            }
            return path.toRealPath().normalize();
        } catch (GitRepositoryLockInvalidPathException e) {
            throw e;
        } catch (IOException e) {
            throw new GitRepositoryLockInvalidPathException("Failed to canonicalize repository path: " + repositoryPath, e);
        } catch (Exception e) {
            throw new GitRepositoryLockInvalidPathException("Invalid repository path syntax: " + repositoryPath, e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
