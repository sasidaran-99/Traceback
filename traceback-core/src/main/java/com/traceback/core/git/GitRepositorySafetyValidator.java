package com.traceback.core.git;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Centralized, hardened validator and opener for Git repositories and filesystem paths.
 * Enforces strict boundary containment, rejects symlink escapes, linked worktrees (.git files),
 * directory climbing, and environment variable overrides, and performs post-open canonical verification.
 */
public final class GitRepositorySafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(GitRepositorySafetyValidator.class);

    private GitRepositorySafetyValidator() {
        // Utility class
    }

    /**
     * Determines whether the active environment indicates a production deployment.
     */
    public static boolean isProduction(Environment env) {
        if (env != null && env.getActiveProfiles() != null && env.getActiveProfiles().length > 0) {
            return Arrays.asList(env.getActiveProfiles()).contains("prod");
        }
        String sysProp = System.getProperty("spring.profiles.active");
        if (sysProp != null && Arrays.asList(sysProp.split("[,;]")).contains("prod")) {
            return true;
        }
        String envVar = System.getenv("SPRING_PROFILES_ACTIVE");
        return envVar != null && Arrays.asList(envVar.split("[,;]")).contains("prod");
    }

    /**
     * Initializes and canonicalizes the allowed repository roots.
     * In production (prod profile), explicitly configured roots are mandatory, must exist, and cannot
     * fall back to implicit project or temporary directories.
     */
    public static List<Path> initializeAllowedRoots(List<String> rawRoots, boolean isProd) {
        List<Path> canonicalRoots = new ArrayList<>();

        if (isProd) {
            if (rawRoots == null || rawRoots.isEmpty()) {
                throw new IllegalStateException(
                        "Production security failure: 'traceback.security.allowed-repo-roots' must be explicitly configured with valid, existing repository roots");
            }
            for (String r : rawRoots) {
                if (r == null || r.trim().isEmpty()) {
                    continue;
                }
                Path path = Path.of(r.trim());
                if (!Files.exists(path)) {
                    throw new IllegalStateException(
                            "Production security failure: Configured repository root does not exist on disk: " + r);
                }
                try {
                    Path canonical = path.toRealPath();
                    if (!canonicalRoots.contains(canonical)) {
                        canonicalRoots.add(canonical);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Production security failure: Failed to resolve canonical path for repository root '" + r + "': " + e.getMessage(), e);
                }
            }
            if (canonicalRoots.isEmpty()) {
                throw new IllegalStateException(
                        "Production security failure: No valid repository roots could be resolved from 'traceback.security.allowed-repo-roots'");
            }
        } else {
            // Development and test behavior
            if (rawRoots != null && !rawRoots.isEmpty()) {
                for (String r : rawRoots) {
                    if (r != null && !r.trim().isEmpty()) {
                        addRootSafely(canonicalRoots, Path.of(r.trim()));
                    }
                }
            } else {
                // Default restricted boundary for local test/dev: current project root and system temp directory
                addRootSafely(canonicalRoots, Path.of("."));
                addRootSafely(canonicalRoots, Path.of(System.getProperty("java.io.tmpdir")));
            }
        }

        return Collections.unmodifiableList(canonicalRoots);
    }

    private static void addRootSafely(List<Path> list, Path path) {
        Path norm = path.toAbsolutePath().normalize();
        try {
            Path real = norm.toRealPath();
            if (!list.contains(real)) {
                list.add(real);
            }
        } catch (IOException ignored) {
            if (!list.contains(norm)) {
                list.add(norm);
            }
        }
    }

    /**
     * Checks if the given path resides within any of the authorized repository roots.
     * Uses component-aware Path.startsWith() rather than string prefix checks.
     */
    public static boolean isPathAuthorized(Path path, List<Path> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty() || path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalized.equals(root) || normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that the repository path:
     * 1. Is non-null and non-blank.
     * 2. Resolves to a canonical existing directory via Path.toRealPath().
     * 3. Resides within the authorized repository roots (preventing symlink/junction breakout).
     * 4. Contains a valid, non-symlink .git DIRECTORY (rejecting .git files, worktrees, and symlinks).
     * 5. Confirms canonical .git resides strictly within the canonical repository root.
     *
     * @param repositoryPath client-supplied repository path string
     * @param allowedRoots   list of canonical authorized root paths
     * @return canonical Path to the repository root
     */
    public static Path validateRepositoryPath(String repositoryPath, List<Path> allowedRoots) {
        if (repositoryPath == null || repositoryPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository path cannot be null or empty");
        }

        Path canonicalRepoPath;
        try {
            // Resolves symlinks and directory junctions on the repository root
            canonicalRepoPath = Path.of(repositoryPath).toRealPath();
        } catch (IOException e) {
            // Path does not exist or is inaccessible. Check normalized path against allowed roots to prevent unauthorized probes
            Path raw = Path.of(repositoryPath).toAbsolutePath().normalize();
            Path parent = raw.getParent();
            Path resolvedCheck = raw;
            if (parent != null && Files.exists(parent)) {
                try {
                    resolvedCheck = parent.toRealPath().resolve(raw.getFileName());
                } catch (IOException ignored) {}
            }
            if (!isPathAuthorized(raw, allowedRoots) && !isPathAuthorized(resolvedCheck, allowedRoots)) {
                log.warn("Access denied: Repository path {} is outside authorized roots", repositoryPath);
                throw new SecurityException("Access denied: Repository path is not within the authorized repository roots");
            }
            throw new IllegalArgumentException("Repository path does not exist or is inaccessible: " + repositoryPath);
        }

        // Boundary check on canonical real path
        if (!isPathAuthorized(canonicalRepoPath, allowedRoots)) {
            log.warn("Access denied: Resolved canonical path {} is outside authorized roots", canonicalRepoPath);
            throw new SecurityException("Access denied: Repository path is not within the authorized repository roots");
        }

        if (!Files.isDirectory(canonicalRepoPath)) {
            throw new IllegalArgumentException("Repository path is not a directory: " + repositoryPath);
        }

        // Validate .git
        Path gitDir = canonicalRepoPath.resolve(Constants.DOT_GIT);
        if (!Files.exists(gitDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Directory is not a Git repository (missing .git directory): " + repositoryPath);
        }

        if (Files.isSymbolicLink(gitDir)) {
            log.warn("Security violation: .git at {} is a symbolic link", gitDir);
            throw new SecurityException("Invalid Git repository: .git cannot be a symbolic link");
        }

        if (Files.isRegularFile(gitDir)) {
            log.warn("Security violation: .git at {} is a regular file (linked worktree)", gitDir);
            throw new SecurityException("Invalid Git repository: Linked worktrees and .git files are not supported");
        }

        if (!Files.isDirectory(gitDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Directory is not a Git repository (missing .git directory): " + repositoryPath);
        }

        // Verify canonical .git stays within canonical repository root
        Path canonicalGitDir;
        try {
            canonicalGitDir = gitDir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve canonical .git directory: " + e.getMessage(), e);
        }

        if (!canonicalGitDir.startsWith(canonicalRepoPath)) {
            log.warn("Security violation: Canonical .git {} escaped repository root {}", canonicalGitDir, canonicalRepoPath);
            throw new SecurityException("Invalid Git repository: .git directory escaped repository boundary");
        }

        if (!isPathAuthorized(canonicalGitDir, allowedRoots)) {
            log.warn("Security violation: Canonical .git {} is outside authorized roots", canonicalGitDir);
            throw new SecurityException("Invalid Git repository: .git directory is outside authorized roots");
        }

        return canonicalRepoPath;
    }

    /**
     * Opens a Git repository with strict security controls:
     * - Configures explicit gitDir and workTree.
     * - Omits readEnvironment() to prevent environment variable override.
     * - Omits findGitDir() to prevent directory climbing to parent repositories.
     * - Sets setMustExist(true).
     * - Rejects bare repositories.
     * - Immediately executes post-open canonical verification on repo.getDirectory() and repo.getWorkTree().
     * - Closes repository on any verification failure before returning.
     *
     * @param canonicalRepoPath pre-validated canonical path to repository root
     * @param allowedRoots       list of canonical authorized root paths
     * @return fully verified, open Repository instance
     * @throws IOException on I/O error
     */
    public static Repository openRepositorySafely(Path canonicalRepoPath, List<Path> allowedRoots) throws IOException {
        Objects.requireNonNull(canonicalRepoPath, "canonicalRepoPath cannot be null");

        Path gitDir = canonicalRepoPath.resolve(Constants.DOT_GIT).toRealPath();

        File repoDirFile = canonicalRepoPath.toFile();
        File gitDirFile = gitDir.toFile();

        // Build explicitly without readEnvironment() and without findGitDir()
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDirFile)
                .setWorkTree(repoDirFile)
                .setMustExist(true)
                .build();

        try {
            if (repo.isBare()) {
                throw new IllegalArgumentException("Bare repositories are not supported: " + canonicalRepoPath);
            }

            File openedWorkTreeFile = repo.getWorkTree();
            File openedGitDirFile = repo.getDirectory();

            if (openedWorkTreeFile == null || openedGitDirFile == null) {
                throw new SecurityException("Opened repository lacks valid worktree or git directory metadata");
            }

            Path openedWorkTree = openedWorkTreeFile.toPath().toRealPath();
            Path openedGitDir = openedGitDirFile.toPath().toRealPath();

            // Post-open verification: must match validated canonical paths exactly
            if (!openedWorkTree.equals(canonicalRepoPath)) {
                log.warn("Post-open violation: Opened worktree {} does not match validated path {}", openedWorkTree, canonicalRepoPath);
                throw new SecurityException("Opened repository worktree '" + openedWorkTree +
                        "' does not match validated repository path '" + canonicalRepoPath + "'");
            }

            if (!openedGitDir.equals(gitDir) || !openedGitDir.startsWith(canonicalRepoPath)) {
                log.warn("Post-open violation: Opened gitDir {} does not match validated gitDir {} or escaped boundary", openedGitDir, gitDir);
                throw new SecurityException("Opened repository git directory '" + openedGitDir +
                        "' does not match validated git directory or escaped repository boundary");
            }

            if (!isPathAuthorized(openedWorkTree, allowedRoots) || !isPathAuthorized(openedGitDir, allowedRoots)) {
                log.warn("Post-open violation: Opened repository paths outside authorized roots: workTree={}, gitDir={}", openedWorkTree, openedGitDir);
                throw new SecurityException("Opened repository is outside authorized security roots");
            }

            return repo;
        } catch (Exception e) {
            repo.close();
            throw e;
        }
    }
}
