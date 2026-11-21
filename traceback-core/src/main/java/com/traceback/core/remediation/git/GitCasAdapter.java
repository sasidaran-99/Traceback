package com.traceback.core.remediation.git;

import java.util.Optional;

/**
 * Focused Git compare-and-swap abstraction protecting against arbitrary filesystem access and unsafe overwrites.
 */
public interface GitCasAdapter {

    boolean isRepositoryAuthorized(String repositoryPath);

    boolean isValidTargetRef(String refName);

    Optional<String> resolveRefSha(String repositoryPath, String refName);

    GitCasResult executeCompareAndSwap(
            String repositoryPath,
            String refName,
            String expectedOldSha,
            String proposedNewSha,
            boolean allowForce
    );
}
