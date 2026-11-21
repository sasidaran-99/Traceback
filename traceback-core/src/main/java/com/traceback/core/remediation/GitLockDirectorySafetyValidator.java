package com.traceback.core.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Validates and resolves the Git repository lock directory.
 * <p>
 * In production profiles ("prod" or "production"):
 * - {@code traceback.git.lock-directory} is mandatory and must not be blank.
 * - The path must be absolute and exist on disk as a directory.
 * - System temporary directories (e.g. {@code java.io.tmpdir}, {@code /tmp}, {@code /var/tmp}) are strictly rejected.
 * - Symbolic links and directory junctions are rejected where supported by the platform.
 * - File creation and retention probe tests verify writability and persistence.
 * - Lock files are never deleted or truncated during startup, validation, or normal operation.
 * <p>
 * In development and test profiles:
 * - Falls back to {@code ${java.io.tmpdir}/traceback-locks} if unconfigured.
 */
public final class GitLockDirectorySafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(GitLockDirectorySafetyValidator.class);

    private GitLockDirectorySafetyValidator() {}

    /**
     * Determines whether the active environment indicates a production deployment.
     */
    public static boolean isProduction(Environment env) {
        if (env != null && env.getActiveProfiles() != null && env.getActiveProfiles().length > 0) {
            return Arrays.stream(env.getActiveProfiles())
                    .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
        }
        String sysProp = System.getProperty("spring.profiles.active");
        if (sysProp != null && Arrays.stream(sysProp.split("[,;]"))
                .anyMatch(p -> p.trim().equalsIgnoreCase("prod") || p.trim().equalsIgnoreCase("production"))) {
            return true;
        }
        String envVar = System.getenv("SPRING_PROFILES_ACTIVE");
        return envVar != null && Arrays.stream(envVar.split("[,;]"))
                .anyMatch(p -> p.trim().equalsIgnoreCase("prod") || p.trim().equalsIgnoreCase("production"));
    }

    /**
     * Returns the canonical system temporary directory.
     */
    public static Path getSystemTempDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize();
    }

    /**
     * Resolves the lock directory according to the active profile rules.
     *
     * @param configuredLockDir configured path string from properties (may be null)
     * @param isProd            true if production profile is active
     * @return canonical validated Path to the lock directory
     */
    public static Path resolveLockDirectory(String configuredLockDir, boolean isProd) {
        return resolveLockDirectory(configuredLockDir, isProd, getSystemTempDirectory());
    }

    /**
     * Resolves the lock directory with an explicit system temporary directory reference (useful for tests).
     *
     * @param configuredLockDir configured path string from properties (may be null)
     * @param isProd            true if production profile is active
     * @param systemTmpDir      base temporary directory considered prohibited in production
     * @return canonical validated Path to the lock directory
     */
    public static Path resolveLockDirectory(String configuredLockDir, boolean isProd, Path systemTmpDir) {
        if (isProd) {
            return validateProductionLockDirectory(configuredLockDir, systemTmpDir);
        } else {
            return resolveDevelopmentLockDirectory(configuredLockDir);
        }
    }

    /**
     * Validates and canonicalizes the production lock directory, enforcing all fail-closed security rules.
     */
    public static Path validateProductionLockDirectory(String configuredLockDir, Path systemTmpDir) {
        // 1. Missing or blank configuration must fail application startup
        if (configuredLockDir == null || configuredLockDir.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration failure: 'traceback.git.lock-directory' must be explicitly configured in production profile"
            );
        }
        String trimmedPath = configuredLockDir.trim();

        // 2. The path must be absolute
        Path rawPath = Path.of(trimmedPath);
        if (!rawPath.isAbsolute()) {
            throw new IllegalStateException(
                    "Production configuration failure: 'traceback.git.lock-directory' must be an absolute path: " + trimmedPath
            );
        }

        // 3. Must exist on disk
        if (!Files.exists(rawPath)) {
            throw new IllegalStateException(
                    "Production configuration failure: Configured lock directory does not exist on disk: " + trimmedPath
            );
        }

        // 4. Must be a directory (reject regular files)
        if (!Files.isDirectory(rawPath)) {
            throw new IllegalStateException(
                    "Production configuration failure: Configured lock directory is not a directory: " + trimmedPath
            );
        }

        // 5. Reject symbolic links and directory junctions where platform supports reliable detection
        if (Files.isSymbolicLink(rawPath)) {
            throw new IllegalStateException(
                    "Production configuration failure: Configured lock directory cannot be a symbolic link: " + trimmedPath
            );
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(rawPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IllegalStateException(
                        "Production configuration failure: Configured lock directory cannot be a symbolic link or directory junction: " + trimmedPath
                );
            }
        } catch (IOException e) {
            throw new IllegalStateException("Production configuration failure: Unable to inspect lock directory attributes: " + trimmedPath, e);
        }

        // 6. Resolve canonical path
        Path canonical;
        try {
            canonical = rawPath.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Production configuration failure: Failed to resolve canonical path for lock directory '" + trimmedPath + "': " + e.getMessage(), e
            );
        }

        if (!Files.isDirectory(canonical)) {
            throw new IllegalStateException(
                    "Production configuration failure: Canonical lock directory is not a directory: " + canonical
            );
        }

        // 7. Reject paths matching or inside system temporary directory
        Path sysTmp = systemTmpDir != null ? systemTmpDir : getSystemTempDirectory();
        Path normSysTmp = sysTmp.toAbsolutePath().normalize();
        Path canonSysTmp = normSysTmp;
        try {
            if (Files.exists(normSysTmp)) {
                canonSysTmp = normSysTmp.toRealPath();
            }
        } catch (Exception ignored) {}

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String normRawStr = rawPath.toAbsolutePath().normalize().toString();
        String canonStr = canonical.toString();
        String normSysTmpStr = normSysTmp.toString();
        String canonSysTmpStr = canonSysTmp.toString();

        boolean equalsTmp = isWindows
                ? normRawStr.equalsIgnoreCase(normSysTmpStr) || canonStr.equalsIgnoreCase(canonSysTmpStr) || canonStr.equalsIgnoreCase(normSysTmpStr)
                : rawPath.toAbsolutePath().normalize().equals(normSysTmp) || canonical.equals(canonSysTmp);

        if (equalsTmp) {
            throw new IllegalStateException(
                    "Production configuration failure: 'traceback.git.lock-directory' cannot be the system temporary directory: " + trimmedPath
            );
        }

        String sep = File.separator;
        String canonSysTmpWithSep = canonSysTmpStr.endsWith(sep) ? canonSysTmpStr : canonSysTmpStr + sep;
        String normSysTmpWithSep = normSysTmpStr.endsWith(sep) ? normSysTmpStr : normSysTmpStr + sep;

        boolean insideTmp = isWindows
                ? canonStr.toLowerCase().startsWith(canonSysTmpWithSep.toLowerCase())
                || normRawStr.toLowerCase().startsWith(normSysTmpWithSep.toLowerCase())
                : canonical.startsWith(canonSysTmp) || rawPath.toAbsolutePath().normalize().startsWith(normSysTmp);

        if (insideTmp) {
            throw new IllegalStateException(
                    "Production configuration failure: 'traceback.git.lock-directory' cannot be inside the system temporary directory: " + trimmedPath
            );
        }

        // Reject well-known standard Unix temporary directory paths
        for (String stdTmp : List.of("/tmp", "/var/tmp")) {
            try {
                Path p = Path.of(stdTmp);
                if (Files.exists(p)) {
                    Path realStd = p.toRealPath();
                    if (canonical.equals(realStd) || canonical.equals(p.toAbsolutePath().normalize())) {
                        throw new IllegalStateException(
                                "Production configuration failure: 'traceback.git.lock-directory' cannot be standard temporary directory: " + trimmedPath
                        );
                    }
                    if (canonical.startsWith(realStd) || canonical.startsWith(p.toAbsolutePath().normalize())) {
                        throw new IllegalStateException(
                                "Production configuration failure: 'traceback.git.lock-directory' cannot be inside standard temporary directory: " + trimmedPath
                        );
                    }
                }
            } catch (Exception ignored) {}
        }

        // 8. Detect directory redirection / symbolic link traversal
        // Using toRealPath(NOFOLLOW_LINKS) resolves Windows 8.3 short names and case without following symlinks.
        Path noFollowRealPath;
        try {
            noFollowRealPath = rawPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException("Production configuration failure: Failed to inspect path for lock directory: " + trimmedPath, e);
        }

        boolean pathsMatch = isWindows
                ? noFollowRealPath.toString().equalsIgnoreCase(canonical.toString())
                : noFollowRealPath.equals(canonical);

        if (!pathsMatch) {
            throw new IllegalStateException(
                    "Production configuration failure: Configured lock directory involves symbolic link or directory redirection (raw="
                            + rawPath.toAbsolutePath().normalize() + ", canonical=" + canonical + ")"
            );
        }

        // 9. Verify directory writability and file creation/retention probe
        verifyDirectoryWritabilityAndProbe(canonical);

        return canonical;
    }

    /**
     * Resolves lock directory for development and test profiles, retaining frictionless defaults.
     */
    public static Path resolveDevelopmentLockDirectory(String configuredLockDir) {
        if (configuredLockDir != null && !configuredLockDir.isBlank()) {
            return Path.of(configuredLockDir).toAbsolutePath().normalize();
        }
        String customDir = System.getProperty("traceback.git.lock-dir");
        if (customDir != null && !customDir.isBlank()) {
            return Path.of(customDir).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "traceback-locks").toAbsolutePath().normalize();
    }

    /**
     * Verifies that the application can create, write to, and retain files in the lock directory.
     * Lock files (repo_*.lock) are never deleted, truncated, or replaced during this probe.
     */
    public static void verifyDirectoryWritabilityAndProbe(Path canonical) {
        Objects.requireNonNull(canonical, "canonical path cannot be null");

        if (!Files.isReadable(canonical)) {
            throw new IllegalStateException(
                    "Production configuration failure: Configured lock directory is not readable by current process: " + canonical
            );
        }
        if (!Files.isWritable(canonical)) {
            throw new IllegalStateException(
                    "Production configuration failure: Configured lock directory is not writable by current process: " + canonical
            );
        }

        // Check POSIX file attributes where supported (Linux/macOS)
        if (canonical.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                java.nio.file.attribute.PosixFileAttributes posixAttrs =
                        Files.readAttributes(canonical, java.nio.file.attribute.PosixFileAttributes.class);
                log.info("Production lock directory POSIX owner: {}, group: {}, permissions: {}",
                        posixAttrs.owner(), posixAttrs.group(),
                        java.nio.file.attribute.PosixFilePermissions.toString(posixAttrs.permissions()));

                if (posixAttrs.permissions().contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                    throw new IllegalStateException(
                            "Production configuration failure: Lock directory cannot be world-writable: " + canonical
                    );
                }
            } catch (IOException e) {
                throw new IllegalStateException("Production configuration failure: Failed to inspect POSIX attributes for: " + canonical, e);
            }
        } else {
            log.info("POSIX file attributes not supported on current platform for lock directory: {}. Relying on Java NIO file permission checks.", canonical);
        }

        // File creation and retention probe (using dedicated temporary probe file, never touching .lock files)
        Path probeFile = canonical.resolve(".tb_lock_probe_" + UUID.randomUUID());
        try {
            Files.writeString(probeFile, "probe_test", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!Files.exists(probeFile) || Files.size(probeFile) == 0) {
                throw new IllegalStateException("Production configuration failure: Failed file write probe in lock directory: " + canonical);
            }
            Files.delete(probeFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Production configuration failure: Failed to create probe file in lock directory: " + canonical + " (" + e.getMessage() + ")", e
            );
        }
    }
}
