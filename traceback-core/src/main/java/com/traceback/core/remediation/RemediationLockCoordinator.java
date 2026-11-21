package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationTargetRefLockEntity;
import com.traceback.core.repository.RemediationTargetRefLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Coordinates Level 0 Target-Ref Locks to enforce global serialization across Git operations and freezes.
 */
@Service
public class RemediationLockCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RemediationLockCoordinator.class);

    private final RemediationTargetRefLockRepository targetRefLockRepository;

    public RemediationLockCoordinator(RemediationTargetRefLockRepository targetRefLockRepository) {
        this.targetRefLockRepository = Objects.requireNonNull(targetRefLockRepository, "targetRefLockRepository cannot be null");
    }

    /**
     * Acquires a shared lock on the target ref (Level 0) for operations performing safe modifications.
     * Throws {@link RemediationTargetRefFrozenException} if the target ref is frozen.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RemediationTargetRefLockEntity acquireTargetRefShare(String refName) {
        Objects.requireNonNull(refName, "refName cannot be null");
        ensureProvisioned(refName);

        RemediationTargetRefLockEntity lockEntity = targetRefLockRepository.findByRefNameForShare(refName)
                .orElseThrow(() -> new IllegalStateException("Target ref lock row unexpectedly missing for: " + refName));

        if (Boolean.TRUE.equals(lockEntity.getIsFrozen())) {
            log.warn("Operation blocked: Target Git ref '{}' is frozen. Reason: {}", refName, lockEntity.getFrozenReason());
            throw new RemediationTargetRefFrozenException(refName, lockEntity.getFrozenReason());
        }

        return lockEntity;
    }

    /**
     * Acquires an exclusive lock on the target ref (Level 0) for administrative operations, reconciliations, or freezes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RemediationTargetRefLockEntity acquireTargetRefExclusive(String refName) {
        Objects.requireNonNull(refName, "refName cannot be null");
        ensureProvisioned(refName);

        return targetRefLockRepository.findByRefNameForUpdate(refName)
                .orElseThrow(() -> new IllegalStateException("Target ref lock row unexpectedly missing for: " + refName));
    }

    /**
     * Freezes a target ref against further remediation operations.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void freezeTargetRef(String refName, String reason) {
        RemediationTargetRefLockEntity lockEntity = acquireTargetRefExclusive(refName);
        lockEntity.setIsFrozen(true);
        lockEntity.setFrozenReason(reason);
        lockEntity.setFrozenAt(Instant.now());
        targetRefLockRepository.save(lockEntity);
        log.info("Target Git ref '{}' has been FROZEN. Reason: {}", refName, reason);
    }

    /**
     * Unfreezes a target ref, allowing remediation operations to proceed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unfreezeTargetRef(String refName) {
        RemediationTargetRefLockEntity lockEntity = acquireTargetRefExclusive(refName);
        lockEntity.setIsFrozen(false);
        lockEntity.setFrozenReason(null);
        lockEntity.setFrozenAt(null);
        targetRefLockRepository.save(lockEntity);
        log.info("Target Git ref '{}' has been UNFROZEN", refName);
    }

    /**
     * Increments and returns the next monotonically increasing fencing token for a target ref.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long incrementFencingToken(String refName) {
        RemediationTargetRefLockEntity lockEntity = acquireTargetRefExclusive(refName);
        long nextToken = lockEntity.getCurrentFencingToken() + 1L;
        lockEntity.setCurrentFencingToken(nextToken);
        targetRefLockRepository.save(lockEntity);
        return nextToken;
    }

    private void ensureProvisioned(String refName) {
        if (!targetRefLockRepository.existsById(refName)) {
            try {
                RemediationTargetRefLockEntity newLock = new RemediationTargetRefLockEntity(refName);
                targetRefLockRepository.saveAndFlush(newLock);
            } catch (Exception e) {
                // Ignore conflict if another thread/transaction provisioned concurrently
                log.debug("Concurrent provisioning handled for ref '{}'", refName);
            }
        }
    }
}
