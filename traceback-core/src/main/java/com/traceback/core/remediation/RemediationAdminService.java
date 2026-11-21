package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationTargetRefLockEntity;
import com.traceback.core.remediation.git.GitCasAdapter;
import com.traceback.core.repository.RemediationTargetRefLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Service managing administrative target-ref operations, including freezing and unfreezing refs.
 * Enforces transactional boundaries satisfying Propagation.MANDATORY on RemediationLockCoordinator,
 * and validates ref names against path traversal and disallowed namespaces.
 */
@Service
public class RemediationAdminService {

    private static final Logger log = LoggerFactory.getLogger(RemediationAdminService.class);

    private final RemediationLockCoordinator lockCoordinator;
    private final GitCasAdapter gitCasAdapter;
    private final RemediationTargetRefLockRepository targetRefLockRepository;

    public RemediationAdminService(
            RemediationLockCoordinator lockCoordinator,
            GitCasAdapter gitCasAdapter,
            RemediationTargetRefLockRepository targetRefLockRepository
    ) {
        this.lockCoordinator = Objects.requireNonNull(lockCoordinator, "lockCoordinator cannot be null");
        this.gitCasAdapter = Objects.requireNonNull(gitCasAdapter, "gitCasAdapter cannot be null");
        this.targetRefLockRepository = Objects.requireNonNull(targetRefLockRepository, "targetRefLockRepository cannot be null");
    }

    /**
     * Administratively freezes a target ref against all mutations.
     * Opens a transaction boundary satisfying RemediationLockCoordinator's MANDATORY propagation requirement.
     */
    @Transactional
    public void freezeTargetRef(String refName, String reason) {
        Objects.requireNonNull(refName, "refName cannot be null");
        if (!gitCasAdapter.isValidTargetRef(refName)) {
            log.warn("Rejected freeze attempt for invalid Git ref: '{}'", refName);
            throw new IllegalArgumentException("Invalid target Git ref name: " + refName);
        }

        log.info("Executing administrative freeze for ref '{}': reason={}", refName, reason);
        lockCoordinator.freezeTargetRef(refName, reason);
    }

    /**
     * Administratively unfreezes a target ref, allowing mutations to proceed.
     * Opens a transaction boundary satisfying RemediationLockCoordinator's MANDATORY propagation requirement.
     */
    @Transactional
    public void unfreezeTargetRef(String refName) {
        Objects.requireNonNull(refName, "refName cannot be null");
        if (!gitCasAdapter.isValidTargetRef(refName)) {
            log.warn("Rejected unfreeze attempt for invalid Git ref: '{}'", refName);
            throw new IllegalArgumentException("Invalid target Git ref name: " + refName);
        }

        log.info("Executing administrative unfreeze for ref '{}'", refName);
        lockCoordinator.unfreezeTargetRef(refName);
    }

    /**
     * Checks whether a target ref is currently frozen.
     */
    @Transactional(readOnly = true)
    public boolean isRefFrozen(String refName) {
        Objects.requireNonNull(refName, "refName cannot be null");
        if (!gitCasAdapter.isValidTargetRef(refName)) {
            throw new IllegalArgumentException("Invalid target Git ref name: " + refName);
        }

        return targetRefLockRepository.findById(refName)
                .map(l -> Boolean.TRUE.equals(l.getIsFrozen()))
                .orElse(false);
    }
}
