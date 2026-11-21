package com.traceback.core.remediation.git;

import com.traceback.core.git.GitRepositorySafetyValidator;
import com.traceback.core.remediation.RemediationFailureClassification;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production JGit implementation of compare-and-swap ref operations.
 * Enforces strict boundary authorization to prevent arbitrary filesystem operations.
 */
@Component
public class JGitCasAdapter implements GitCasAdapter {

    private static final Logger log = LoggerFactory.getLogger(JGitCasAdapter.class);

    private final List<Path> allowedRoots;

    public JGitCasAdapter() {
        this((List<String>) null, false);
    }

    @Autowired
    public JGitCasAdapter(
            @Value("${traceback.security.allowed-repo-roots:}") String allowedRootsConfig,
            @Autowired(required = false) Environment env
    ) {
        this(parseRoots(allowedRootsConfig), GitRepositorySafetyValidator.isProduction(env));
    }

    public JGitCasAdapter(String allowedRootsConfig) {
        this(parseRoots(allowedRootsConfig), GitRepositorySafetyValidator.isProduction(null));
    }

    public JGitCasAdapter(List<String> allowedRoots) {
        this(allowedRoots, GitRepositorySafetyValidator.isProduction(null));
    }

    public JGitCasAdapter(List<String> allowedRoots, boolean isProd) {
        this.allowedRoots = GitRepositorySafetyValidator.initializeAllowedRoots(allowedRoots, isProd);
        log.info("JGitCasAdapter initialized with {} authorized repository root(s): {}",
                this.allowedRoots.size(), this.allowedRoots);
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

    public List<Path> getAllowedRoots() {
        return Collections.unmodifiableList(allowedRoots);
    }

    @Override
    public boolean isRepositoryAuthorized(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.trim().isEmpty()) {
            return false;
        }
        try {
            Path path = Path.of(repositoryPath);
            Path resolved = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
            return GitRepositorySafetyValidator.isPathAuthorized(resolved, allowedRoots);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isValidTargetRef(String refName) {
        if (refName == null || refName.isBlank()) {
            return false;
        }
        // Reject traversal sequences, backslashes, double slashes, and control characters
        if (refName.contains("..") || refName.contains("\\") || refName.contains("//") || refName.contains(" ")) {
            return false;
        }
        // Reject root-level symbolic refs like HEAD
        String trimmed = refName.trim();
        if ("HEAD".equalsIgnoreCase(trimmed) || "FETCH_HEAD".equalsIgnoreCase(trimmed)
                || "ORIG_HEAD".equalsIgnoreCase(trimmed) || "MERGE_HEAD".equalsIgnoreCase(trimmed)) {
            return false;
        }
        // Restrict strictly to authorized namespaces: refs/heads/ and refs/tags/
        if (!trimmed.startsWith(Constants.R_HEADS) && !trimmed.startsWith(Constants.R_TAGS)) {
            return false;
        }
        // Enforce JGit official ref name validation
        return Repository.isValidRefName(trimmed);
    }

    @Override
    public Optional<String> resolveRefSha(String repositoryPath, String refName) {
        Objects.requireNonNull(repositoryPath, "repositoryPath cannot be null");
        Objects.requireNonNull(refName, "refName cannot be null");

        if (!isValidTargetRef(refName)) {
            log.warn("Ref resolution rejected: Ref name '{}' is malformed, unauthorized, or contains traversal", refName);
            return Optional.empty();
        }

        if (!isRepositoryAuthorized(repositoryPath)) {
            log.warn("Access denied: Repository path '{}' is not within authorized roots", repositoryPath);
            return Optional.empty();
        }

        try (Repository repo = openRepository(repositoryPath)) {
            Ref ref = repo.exactRef(refName);
            if (ref != null && ref.isSymbolic()) {
                log.warn("Ref '{}' is symbolic and cannot be targeted directly", refName);
                return Optional.empty();
            }
            if (ref != null && ref.getObjectId() != null) {
                return Optional.of(ref.getObjectId().name());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to resolve ref '{}' in repo '{}': {}", refName, repositoryPath, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public GitCasResult executeCompareAndSwap(
            String repositoryPath,
            String refName,
            String expectedOldSha,
            String proposedNewSha,
            boolean allowForce
    ) {
        Objects.requireNonNull(repositoryPath, "repositoryPath cannot be null");
        Objects.requireNonNull(refName, "refName cannot be null");
        Objects.requireNonNull(expectedOldSha, "expectedOldSha cannot be null");
        Objects.requireNonNull(proposedNewSha, "proposedNewSha cannot be null");

        if (!isValidTargetRef(refName)) {
            log.error("CAS rejected: target ref '{}' is malformed, unauthorized, or contains path traversal", refName);
            return GitCasResult.failure(null, null, RemediationFailureClassification.VALIDATION_FAILURE,
                    "Invalid target ref: Ref name must be a valid, fully qualified Git ref in refs/heads/ or refs/tags/");
        }

        if (!isRepositoryAuthorized(repositoryPath)) {
            log.error("CAS rejected: repository '{}' is unauthorized", repositoryPath);
            return GitCasResult.failure(null, null, RemediationFailureClassification.UNAUTHORIZED,
                    "Repository path is outside authorized boundaries");
        }

        try (Repository repo = openRepository(repositoryPath)) {
            ObjectId proposedNewObjId;
            try {
                proposedNewObjId = ObjectId.fromString(proposedNewSha);
            } catch (IllegalArgumentException e) {
                return GitCasResult.failure(null, null, RemediationFailureClassification.VALIDATION_FAILURE,
                        "Invalid proposed new SHA: " + proposedNewSha);
            }

            ObjectId expectedOldObjId;
            boolean isNewRefExpected = expectedOldSha.isBlank() || ObjectId.zeroId().name().equalsIgnoreCase(expectedOldSha);
            if (isNewRefExpected) {
                expectedOldObjId = ObjectId.zeroId();
            } else {
                try {
                    expectedOldObjId = ObjectId.fromString(expectedOldSha);
                } catch (IllegalArgumentException e) {
                    return GitCasResult.failure(null, null, RemediationFailureClassification.VALIDATION_FAILURE,
                            "Invalid expected old SHA: " + expectedOldSha);
                }
            }

            Ref initialRef = repo.exactRef(refName);
            if (initialRef != null && initialRef.isSymbolic()) {
                log.error("CAS rejected: target ref '{}' is a symbolic ref", refName);
                return GitCasResult.failure(null, null, RemediationFailureClassification.VALIDATION_FAILURE,
                        "Symbolic refs cannot be targeted for compare-and-swap mutation: " + refName);
            }
            String currentSha = initialRef != null && initialRef.getObjectId() != null ? initialRef.getObjectId().name() : null;

            RefUpdate update = repo.updateRef(refName);
            update.setExpectedOldObjectId(expectedOldObjId);
            update.setNewObjectId(proposedNewObjId);
            update.setForceUpdate(allowForce);
            update.setRefLogMessage("TraceBack 2.0 remediation CAS", false);

            RefUpdate.Result result = update.update();
            log.info("JGit RefUpdate on '{}' in '{}' returned result: {}", refName, repositoryPath, result);

            return switch (result) {
                case NEW -> GitCasResult.success(currentSha, proposedNewSha, result);
                case FAST_FORWARD -> GitCasResult.success(currentSha, proposedNewSha, result);
                case FORCED -> {
                    if (allowForce) {
                        yield GitCasResult.success(currentSha, proposedNewSha, result);
                    } else {
                        yield GitCasResult.failure(currentSha, result, RemediationFailureClassification.GIT_CAS_MISMATCH,
                                "Non-fast-forward update occurred without explicit force authorization");
                    }
                }
                case NO_CHANGE -> {
                    if (proposedNewSha.equalsIgnoreCase(currentSha)) {
                        yield GitCasResult.success(currentSha, proposedNewSha, result);
                    } else {
                        yield GitCasResult.failure(currentSha, result, RemediationFailureClassification.GIT_CAS_MISMATCH,
                                "Ref unchanged but did not match target SHA");
                    }
                }
                case LOCK_FAILURE -> {
                    // Re-read ref to distinguish true filesystem lock contention (.lock file) from CAS mismatch
                    Ref freshRef = repo.exactRef(refName);
                    String freshSha = freshRef != null && freshRef.getObjectId() != null ? freshRef.getObjectId().name() : null;
                    if (freshSha == null && !isNewRefExpected) {
                        yield GitCasResult.failure(null, result, RemediationFailureClassification.GIT_CAS_MISMATCH,
                                String.format("CAS Mismatch: ref '%s' does not exist or was deleted (expected %s)", refName, expectedOldSha));
                    } else if (freshSha != null && !freshSha.equalsIgnoreCase(expectedOldSha)) {
                        yield GitCasResult.failure(freshSha, result, RemediationFailureClassification.GIT_CAS_MISMATCH,
                                String.format("CAS Mismatch: ref '%s' currently at %s (expected %s)", refName, freshSha, expectedOldSha));
                    } else {
                        yield GitCasResult.failure(freshSha, result, RemediationFailureClassification.GIT_LOCK_FAILURE,
                                "Ref lock contention: unable to acquire ref lock");
                    }
                }
                case REJECTED -> GitCasResult.failure(currentSha, result, RemediationFailureClassification.GIT_CAS_MISMATCH,
                        "CAS Rejected: ref was modified concurrently or proposed commit is not a fast-forward");
                case IO_FAILURE -> GitCasResult.failure(currentSha, result, RemediationFailureClassification.GIT_IO_FAILURE,
                        "I/O failure occurred while updating Git ref");
                case NOT_ATTEMPTED -> GitCasResult.failure(currentSha, result, RemediationFailureClassification.GIT_IO_FAILURE,
                        "Ref update was not attempted");
                default -> GitCasResult.failure(currentSha, result, RemediationFailureClassification.SYSTEM_CRASH_OR_TIMEOUT,
                        "Unrecognized JGit ref update result: " + result);
            };

        } catch (SecurityException e) {
            log.error("CAS security violation on repo '{}': {}", repositoryPath, e.getMessage());
            return GitCasResult.failure(null, null, RemediationFailureClassification.UNAUTHORIZED,
                    "Security violation: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("CAS validation failure on repo '{}': {}", repositoryPath, e.getMessage());
            return GitCasResult.failure(null, null, RemediationFailureClassification.VALIDATION_FAILURE,
                    "Validation failure: " + e.getMessage());
        } catch (IOException e) {
            log.error("Exception executing JGit CAS on '{}' in '{}': {}", refName, repositoryPath, e.getMessage(), e);
            return GitCasResult.failure(null, null, RemediationFailureClassification.GIT_IO_FAILURE,
                    "Git repository I/O error: " + e.getMessage());
        }
    }

    private Repository openRepository(String repoPath) throws IOException {
        Path canonicalPath = GitRepositorySafetyValidator.validateRepositoryPath(repoPath, allowedRoots);
        return GitRepositorySafetyValidator.openRepositorySafely(canonicalPath, allowedRoots);
    }
}
