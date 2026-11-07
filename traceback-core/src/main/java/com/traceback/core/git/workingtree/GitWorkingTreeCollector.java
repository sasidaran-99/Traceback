package com.traceback.core.git.workingtree;

import com.traceback.core.git.GitCollector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated read-only collector for inspecting uncommitted Git working tree state and filesystem discrepancies.
 * Closes the architectural gap between historical committed Git analysis and uncommitted filesystem changes.
 * Never modifies, restores, stages, commits, or deletes files in investigated repositories.
 */
@Component
public class GitWorkingTreeCollector {

    private static final Logger log = LoggerFactory.getLogger(GitWorkingTreeCollector.class);

    private final GitCollector gitCollector;
    private final List<Path> allowedRoots;

    public GitWorkingTreeCollector() {
        this(new GitCollector(), (List<String>) null);
    }

    @Autowired
    public GitWorkingTreeCollector(
            GitCollector gitCollector,
            @Value("${traceback.security.allowed-repo-roots:}") String allowedRootsConfig
    ) {
        this(gitCollector, parseRoots(allowedRootsConfig));
    }

    public GitWorkingTreeCollector(GitCollector gitCollector, List<String> allowedRoots) {
        this.gitCollector = gitCollector != null ? gitCollector : new GitCollector();
        this.allowedRoots = new ArrayList<>();
        if (allowedRoots != null && !allowedRoots.isEmpty()) {
            for (String r : allowedRoots) {
                addRoot(Path.of(r));
            }
        } else if (this.gitCollector != null && !this.gitCollector.getAllowedRoots().isEmpty()) {
            this.allowedRoots.addAll(this.gitCollector.getAllowedRoots());
        } else {
            addRoot(Path.of("."));
            addRoot(Path.of(System.getProperty("java.io.tmpdir")));
        }
    }

    private static List<String> parseRoots(String config) {
        if (config == null || config.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = config.split("[,;]");
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private void addRoot(Path path) {
        Path norm = path.toAbsolutePath().normalize();
        if (!this.allowedRoots.contains(norm)) {
            this.allowedRoots.add(norm);
        }
        try {
            Path real = norm.toRealPath();
            if (!this.allowedRoots.contains(real)) {
                this.allowedRoots.add(real);
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isPathAuthorized(Path path) {
        if (allowedRoots.isEmpty()) {
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
     * Validates that the provided path exists, is within an authorized repository root,
     * and contains a valid Git repository without following symlink escapes.
     */
    public Path validateRepositoryPath(String repositoryPath) {
        if (gitCollector != null) {
            try {
                return gitCollector.validateRepositoryPath(repositoryPath);
            } catch (Exception e) {
                // If gitCollector doesn't have it authorized, check our own allowed roots
                Path realPath;
                try {
                    realPath = Path.of(repositoryPath).toRealPath();
                } catch (IOException ex) {
                    Path raw = Path.of(repositoryPath).toAbsolutePath().normalize();
                    if (!isPathAuthorized(raw)) {
                        log.warn("Access denied: Repository path {} is outside authorized roots", repositoryPath);
                        throw new SecurityException("Access denied: Repository path is not within the authorized repository roots");
                    }
                    throw new IllegalArgumentException("Repository path does not exist or is inaccessible");
                }

                if (!isPathAuthorized(realPath)) {
                    log.warn("Access denied: Resolved real path {} is outside authorized roots", realPath);
                    throw new SecurityException("Access denied: Repository path is not within the authorized repository roots");
                }

                if (!Files.isDirectory(realPath)) {
                    throw new IllegalArgumentException("Repository path is not a directory");
                }

                Path gitDir = realPath.resolve(".git");
                if (!Files.exists(gitDir)) {
                    throw new IllegalArgumentException("Directory is not a Git repository (missing .git directory)");
                }

                return realPath;
            }
        }
        throw new IllegalStateException("GitCollector is not initialized");
    }

    /**
     * Collects all uncommitted working tree discrepancies using read-only JGit inspection.
     *
     * @param repositoryPath local filesystem path to the Git repository
     * @return WorkingTreeSnapshot containing all missing, modified, untracked, and staged discrepancies
     */
    public WorkingTreeSnapshot collectWorkingTree(String repositoryPath) {
        Path repoPath = validateRepositoryPath(repositoryPath);
        Instant observedAt = Instant.now();
        List<WorkingTreeEvidence> evidenceList = new ArrayList<>();
        String headSha = "UNKNOWN";

        try (Git git = Git.open(repoPath.toFile())) {
            Repository repo = git.getRepository();
            ObjectId headId = repo.resolve(Constants.HEAD);
            RevTree headTree = null;
            if (headId != null) {
                headSha = headId.getName();
                try (RevWalk rw = new RevWalk(repo)) {
                    RevCommit headCommit = rw.parseCommit(headId);
                    headTree = headCommit.getTree();
                }
            }

            // Execute read-only StatusCommand
            Status status = git.status().call();

            // 1. Missing tracked files (unstaged deletions)
            for (String missingPath : status.getMissing()) {
                evidenceList.add(inspectMissingFile(repoPath, repo, headTree, headSha, missingPath, observedAt));
            }

            // 2. Modified tracked files (unstaged modifications)
            for (String modPath : status.getModified()) {
                evidenceList.add(inspectExistingChange(
                        repoPath, repo, headTree, headSha, modPath,
                        WorkingTreeChangeType.MODIFIED, WorkingTreeStagingState.UNSTAGED, observedAt
                ));
            }

            // 3. Untracked files (uncommitted new files on disk)
            for (String untrackedPath : status.getUntracked()) {
                evidenceList.add(inspectExistingChange(
                        repoPath, repo, headTree, headSha, untrackedPath,
                        WorkingTreeChangeType.UNTRACKED, WorkingTreeStagingState.UNTRACKED, observedAt
                ));
            }

            // 4. Staged additions (new files in index)
            for (String addedPath : status.getAdded()) {
                evidenceList.add(inspectExistingChange(
                        repoPath, repo, headTree, headSha, addedPath,
                        WorkingTreeChangeType.STAGED_ADD, WorkingTreeStagingState.STAGED, observedAt
                ));
            }

            // 5. Staged modifications (changed in index)
            for (String changedPath : status.getChanged()) {
                evidenceList.add(inspectExistingChange(
                        repoPath, repo, headTree, headSha, changedPath,
                        WorkingTreeChangeType.STAGED_MODIFY, WorkingTreeStagingState.STAGED, observedAt
                ));
            }

            // 6. Staged deletions (removed in index)
            for (String removedPath : status.getRemoved()) {
                evidenceList.add(inspectMissingFileWithChangeType(
                        repoPath, repo, headTree, headSha, removedPath,
                        WorkingTreeChangeType.STAGED_DELETE, WorkingTreeStagingState.STAGED, observedAt
                ));
            }

            // 7. Conflicting files
            for (String conflictPath : status.getConflicting()) {
                evidenceList.add(inspectExistingChange(
                        repoPath, repo, headTree, headSha, conflictPath,
                        WorkingTreeChangeType.CONFLICT, WorkingTreeStagingState.CONFLICTING, observedAt
                ));
            }

            log.info("Collected working tree snapshot for repo at {}: {} changes, {} missing tracked files (HEAD: {})",
                    repoPath, evidenceList.size(), status.getMissing().size(), headSha);

            return new WorkingTreeSnapshot(repoPath.toString(), headSha, observedAt, evidenceList);

        } catch (IOException e) {
            log.error("Failed to inspect working tree for repository at {}", repoPath, e);
            throw new RuntimeException("Failed to inspect working tree: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error executing JGit status command on {}", repoPath, e);
            throw new RuntimeException("Git status command failed: " + e.getMessage(), e);
        }
    }

    private WorkingTreeEvidence inspectMissingFile(Path repoRoot, Repository repo, RevTree headTree,
                                                  String headSha, String relativePath, Instant observedAt) {
        return inspectMissingFileWithChangeType(
                repoRoot, repo, headTree, headSha, relativePath,
                WorkingTreeChangeType.MISSING, WorkingTreeStagingState.UNSTAGED, observedAt
        );
    }

    private WorkingTreeEvidence inspectMissingFileWithChangeType(
            Path repoRoot, Repository repo, RevTree headTree, String headSha,
            String relativePath, WorkingTreeChangeType changeType,
            WorkingTreeStagingState stagingState, Instant observedAt
    ) {
        String cleanPath = relativePath.replace('\\', '/');
        boolean expectedInHead = isPathPresentInHead(repo, headTree, cleanPath);

        // Security check: ensure path does not escape repository root
        Path resolved = repoRoot.resolve(cleanPath).normalize();
        if (!resolved.startsWith(repoRoot)) {
            log.warn("Path traversal attempt detected in working tree: {}", cleanPath);
            throw new SecurityException("Illegal path traversal detected: " + cleanPath);
        }

        boolean physicallyExists = Files.exists(resolved);
        boolean isReadable = physicallyExists && Files.isReadable(resolved);
        boolean isSymlink = Files.isSymbolicLink(resolved);
        String affectedModule = inferAffectedModule(cleanPath);

        Long fileSize = null;
        Instant lastMod = null;
        if (physicallyExists) {
            try {
                fileSize = Files.size(resolved);
                lastMod = Files.getLastModifiedTime(resolved).toInstant();
            } catch (IOException ignored) {
            }
        }

        return new WorkingTreeEvidence(
                null,
                repoRoot.toString(),
                cleanPath,
                changeType,
                stagingState,
                headSha,
                physicallyExists,
                isReadable,
                isSymlink,
                observedAt,
                "WORKING_TREE",
                affectedModule,
                expectedInHead,
                fileSize,
                lastMod
        );
    }

    private WorkingTreeEvidence inspectExistingChange(
            Path repoRoot, Repository repo, RevTree headTree, String headSha,
            String relativePath, WorkingTreeChangeType changeType,
            WorkingTreeStagingState stagingState, Instant observedAt
    ) {
        String cleanPath = relativePath.replace('\\', '/');
        boolean expectedInHead = isPathPresentInHead(repo, headTree, cleanPath);

        Path resolved = repoRoot.resolve(cleanPath).normalize();
        if (!resolved.startsWith(repoRoot)) {
            throw new SecurityException("Illegal path traversal detected: " + cleanPath);
        }

        boolean physicallyExists = Files.exists(resolved);
        boolean isReadable = physicallyExists && Files.isReadable(resolved);
        boolean isSymlink = Files.isSymbolicLink(resolved);
        String affectedModule = inferAffectedModule(cleanPath);

        Long fileSize = null;
        Instant lastMod = null;
        if (physicallyExists) {
            try {
                fileSize = Files.size(resolved);
                lastMod = Files.getLastModifiedTime(resolved).toInstant();
            } catch (IOException ignored) {
            }
        }

        return new WorkingTreeEvidence(
                null,
                repoRoot.toString(),
                cleanPath,
                changeType,
                stagingState,
                headSha,
                physicallyExists,
                isReadable,
                isSymlink,
                observedAt,
                "WORKING_TREE",
                affectedModule,
                expectedInHead,
                fileSize,
                lastMod
        );
    }

    private boolean isPathPresentInHead(Repository repo, RevTree headTree, String path) {
        if (repo == null || headTree == null) {
            return false;
        }
        try {
            TreeWalk treeWalk = TreeWalk.forPath(repo, path, headTree);
            return treeWalk != null;
        } catch (IOException e) {
            log.warn("Failed to check path {} in HEAD tree", path, e);
            return false;
        }
    }

    /**
     * Determines the functional module or subsystem associated with a file path.
     */
    public static String inferAffectedModule(String path) {
        if (path == null || path.isBlank()) {
            return "root";
        }
        String p = path.replace('\\', '/');

        if (p.contains("db/migration")) {
            return "backend/db/migration";
        }
        if (p.contains("backend/src/test")) {
            return "backend/test";
        }
        if (p.contains("backend/src/main")) {
            return "backend/main";
        }
        if (p.contains("modules/")) {
            int idx = p.indexOf("modules/");
            int nextSlash = p.indexOf('/', idx + "modules/".length());
            if (nextSlash > idx) {
                return p.substring(0, nextSlash);
            }
            return p.substring(0, idx + "modules/".length());
        }
        if (p.contains("core/")) {
            int idx = p.indexOf("core/");
            int nextSlash = p.indexOf('/', idx + "core/".length());
            if (nextSlash > idx) {
                return p.substring(0, nextSlash);
            }
            return "backend/core";
        }

        // Generic top-level folder grouping
        int firstSlash = p.indexOf('/');
        if (firstSlash > 0) {
            return p.substring(0, firstSlash);
        }
        return "root";
    }
}
