package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.remediation.git.GitCasAdapter;
import com.traceback.core.remediation.git.GitCasResult;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.core.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates remediation operations across separate PostgreSQL transactions and JGit CAS execution.
 * Enforces strict four-phase protocol:
 * Phase A (Durable Intent) -> Phase B (Non-transactional Git CAS) -> Phase C (Durable Success/Failure)
 */
@Service
public class RemediationExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RemediationExecutionCoordinator.class);
    private static final long LEASE_DURATION_SECONDS = 60L;

    private final RemediationOperationRepository operationRepository;
    private final RemediationLockCoordinator lockCoordinator;
    private final RemediationJournalService journalService;
    private final GitCasAdapter gitCasAdapter;
    private final SecretRedactor secretRedactor;
    private final RemediationProcessLockManager processLockManager;
    private final GitRepositoryLockManager repositoryLockManager;

    @Autowired
    public RemediationExecutionCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            SecretRedactor secretRedactor,
            @Autowired(required = false) GitRepositoryLockManager repositoryLockManager
    ) {
        this(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                secretRedactor,
                new RemediationProcessLockManager(),
                repositoryLockManager != null ? repositoryLockManager : new DefaultGitRepositoryLockManager()
        );
    }

    public RemediationExecutionCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            SecretRedactor secretRedactor
    ) {
        this(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                secretRedactor,
                new RemediationProcessLockManager(),
                new DefaultGitRepositoryLockManager()
        );
    }

    public RemediationExecutionCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            SecretRedactor secretRedactor,
            RemediationProcessLockManager processLockManager
    ) {
        this(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                secretRedactor,
                processLockManager,
                new DefaultGitRepositoryLockManager()
        );
    }

    public RemediationExecutionCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            SecretRedactor secretRedactor,
            RemediationProcessLockManager processLockManager,
            GitRepositoryLockManager repositoryLockManager
    ) {
        this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository cannot be null");
        this.lockCoordinator = Objects.requireNonNull(lockCoordinator, "lockCoordinator cannot be null");
        this.journalService = Objects.requireNonNull(journalService, "journalService cannot be null");
        this.gitCasAdapter = Objects.requireNonNull(gitCasAdapter, "gitCasAdapter cannot be null");
        this.secretRedactor = Objects.requireNonNull(secretRedactor, "secretRedactor cannot be null");
        this.processLockManager = processLockManager != null ? processLockManager : new RemediationProcessLockManager();
        this.repositoryLockManager = repositoryLockManager != null ? repositoryLockManager : new DefaultGitRepositoryLockManager();
    }

    public RemediationProcessLockManager getProcessLockManager() {
        return processLockManager;
    }

    public GitRepositoryLockManager getRepositoryLockManager() {
        return repositoryLockManager;
    }

    /**
     * Creates and records a new remediation operation proposal.
     * Idempotent: returns existing operation if idempotency key matches.
     */
    @Transactional
    public RemediationOperationEntity proposeOperation(
            UUID investigationId,
            String idempotencyKey,
            String repositoryPath,
            String targetRef,
            String expectedOldSha,
            String proposedNewSha,
            RemediationOperationType operationType,
            String initiatedBy
    ) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(repositoryPath, "repositoryPath cannot be null");
        Objects.requireNonNull(targetRef, "targetRef cannot be null");
        Objects.requireNonNull(expectedOldSha, "expectedOldSha cannot be null");
        Objects.requireNonNull(proposedNewSha, "proposedNewSha cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        Objects.requireNonNull(initiatedBy, "initiatedBy cannot be null");

        if (!gitCasAdapter.isRepositoryAuthorized(repositoryPath)) {
            throw new IllegalArgumentException("Repository path is outside authorized security roots: " + repositoryPath);
        }

        Optional<RemediationOperationEntity> existing = operationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            RemediationOperationEntity op = existing.get();
            if (op.getTargetRef().equals(targetRef) && op.getProposedNewSha().equalsIgnoreCase(proposedNewSha)) {
                log.info("Idempotent proposal match found for key '{}': operationId={}", idempotencyKey, op.getId());
                return op;
            } else {
                throw new RemediationConcurrencyConflictException(
                        "Idempotency conflict: key '" + idempotencyKey + "' already exists with different operation parameters"
                );
            }
        }

        // Level 0 lock check: verify ref is not frozen
        lockCoordinator.acquireTargetRefShare(targetRef);

        RemediationOperationEntity op = new RemediationOperationEntity();
        op.setId(UUID.randomUUID());
        op.setInvestigationId(investigationId);
        op.setIdempotencyKey(idempotencyKey);
        op.setRepositoryPath(repositoryPath);
        op.setTargetRef(targetRef);
        op.setExpectedOldSha(expectedOldSha.toLowerCase());
        op.setProposedNewSha(proposedNewSha.toLowerCase());
        op.setOperationType(operationType);
        op.setStatus(RemediationOperationStatus.CREATED);
        op.setFencingToken(1L);
        op.setInitiatedBy(initiatedBy);

        RemediationOperationEntity saved = operationRepository.save(op);

        journalService.appendEvent(
                saved.getId(),
                saved.getFencingToken(),
                RemediationOperationEventType.CREATED,
                null,
                RemediationOperationStatus.CREATED,
                targetRef,
                expectedOldSha,
                proposedNewSha,
                null,
                "Proposed by principal: " + initiatedBy
        );

        log.info("Remediation operation proposed: id={}, targetRef={}, key={}", saved.getId(), targetRef, idempotencyKey);
        return saved;
    }

    /**
     * Phase A: Records durable intent within an isolated database transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RemediationOperationEntity recordDurableIntent(UUID operationId, String workerId, Long fencingToken) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(workerId, "workerId cannot be null");
        Objects.requireNonNull(fencingToken, "fencingToken cannot be null");

        RemediationOperationEntity op = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        // Level 0 lock: target ref share check
        lockCoordinator.acquireTargetRefShare(op.getTargetRef());

        // Fencing token validation
        if (!op.getFencingToken().equals(fencingToken)) {
            throw new StaleFencingTokenException(fencingToken, op.getFencingToken());
        }

        // State machine validation
        RemediationStateMachine.validateTransition(op.getStatus(), RemediationOperationStatus.PRE_GIT_CAS_INTENT);

        RemediationOperationStatus prevStatus = op.getStatus();
        op.setStatus(RemediationOperationStatus.PRE_GIT_CAS_INTENT);
        op.setWorkerId(workerId);
        op.setLeaseExpiresAt(Instant.now().plusSeconds(LEASE_DURATION_SECONDS));

        RemediationOperationEntity saved = operationRepository.save(op);

        journalService.appendEvent(
                operationId,
                fencingToken,
                RemediationOperationEventType.PRE_GIT_CAS_INTENT,
                prevStatus,
                RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                op.getTargetRef(),
                op.getExpectedOldSha(),
                op.getProposedNewSha(),
                null,
                "Worker " + workerId + " acquired intent lease until " + op.getLeaseExpiresAt()
        );

        log.info("Phase A complete: Durable intent committed for operation {}", operationId);
        return saved;
    }

    /**
     * Phase B: Executes compare-and-swap ref update OUTSIDE database transaction.
     */
    public GitCasResult executeGitCas(UUID operationId) {
        return executeGitCas(operationId, null);
    }

    /**
     * Phase B: Executes compare-and-swap ref update OUTSIDE database transaction,
     * validating that the worker's fencing token is still current and the lease has not expired.
     */
    public GitCasResult executeGitCas(UUID operationId, Long expectedFencingToken) {
        RemediationOperationEntity op = operationRepository.findById(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        if (expectedFencingToken != null && !op.getFencingToken().equals(expectedFencingToken)) {
            log.error("Fencing check failed before Git CAS for operation {}: expected {}, found {}",
                    operationId, expectedFencingToken, op.getFencingToken());
            throw new StaleFencingTokenException(expectedFencingToken, op.getFencingToken());
        }

        if (op.getStatus() != RemediationOperationStatus.PRE_GIT_CAS_INTENT) {
            log.error("Operation {} is no longer in PRE_GIT_CAS_INTENT status: {}", operationId, op.getStatus());
            throw new IllegalStateException("Operation " + operationId + " is no longer in PRE_GIT_CAS_INTENT status: " + op.getStatus());
        }

        if (op.getLeaseExpiresAt() != null && Instant.now().isAfter(op.getLeaseExpiresAt())) {
            log.error("Worker lease expired at {} before Git CAS for operation {}", op.getLeaseExpiresAt(), operationId);
            throw new IllegalStateException("Worker lease expired at " + op.getLeaseExpiresAt() + " before Git CAS execution");
        }

        // Invariant: Verify repository lock is held by current thread
        if (!repositoryLockManager.isLockedByCurrentThread(op.getRepositoryPath())) {
            log.error("Execution violation: Repository lock not held by current thread for '{}' during operation {}",
                    op.getRepositoryPath(), operationId);
            throw new IllegalStateException("Cannot execute Git CAS: repository lock is not held by current thread for " + op.getRepositoryPath());
        }

        log.info("Phase B starting: Executing Git CAS on '{}' for operation {}", op.getTargetRef(), operationId);
        return gitCasAdapter.executeCompareAndSwap(
                op.getRepositoryPath(),
                op.getTargetRef(),
                op.getExpectedOldSha(),
                op.getProposedNewSha(),
                false
        );
    }

    /**
     * Phase C: Records durable success or failure within an isolated database transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RemediationOperationEntity recordDurableResult(
            UUID operationId,
            String workerId,
            Long fencingToken,
            GitCasResult casResult
    ) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(workerId, "workerId cannot be null");
        Objects.requireNonNull(fencingToken, "fencingToken cannot be null");
        Objects.requireNonNull(casResult, "casResult cannot be null");

        RemediationOperationEntity op = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        // Level 0 lock
        lockCoordinator.acquireTargetRefShare(op.getTargetRef());

        // Fencing token verification
        if (!op.getFencingToken().equals(fencingToken)) {
            throw new StaleFencingTokenException(fencingToken, op.getFencingToken());
        }

        // Must be in PRE_GIT_CAS_INTENT
        if (op.getStatus() != RemediationOperationStatus.PRE_GIT_CAS_INTENT) {
            throw new RemediationInvalidStateTransitionException(op.getStatus(), RemediationOperationStatus.PERSISTED);
        }

        if (casResult.success()) {
            op.setStatus(RemediationOperationStatus.PERSISTED);
            op.setLeaseExpiresAt(null);
            op.setFailureClassification(null);
            op.setFailureDetails(null);

            journalService.appendEvent(
                    operationId,
                    fencingToken,
                    RemediationOperationEventType.GIT_CAS_SUCCEEDED,
                    RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                    RemediationOperationStatus.GIT_CAS_SUCCEEDED,
                    op.getTargetRef(),
                    op.getExpectedOldSha(),
                    casResult.resultingSha(),
                    casResult.jgitResult() != null ? casResult.jgitResult().name() : null,
                    "Git CAS confirmed successful"
            );

            journalService.appendEvent(
                    operationId,
                    fencingToken,
                    RemediationOperationEventType.PERSISTED,
                    RemediationOperationStatus.GIT_CAS_SUCCEEDED,
                    RemediationOperationStatus.PERSISTED,
                    op.getTargetRef(),
                    op.getExpectedOldSha(),
                    casResult.resultingSha(),
                    null,
                    "Remediation operation completed successfully"
            );

            log.info("Phase C complete: Operation {} successfully PERSISTED", operationId);
        } else {
            op.setStatus(RemediationOperationStatus.FAILED);
            op.setLeaseExpiresAt(null);
            op.setFailureClassification(casResult.failureClassification());
            op.setFailureDetails(casResult.failureMessage());

            journalService.appendEvent(
                    operationId,
                    fencingToken,
                    RemediationOperationEventType.GIT_CAS_FAILED,
                    RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                    RemediationOperationStatus.FAILED,
                    op.getTargetRef(),
                    op.getExpectedOldSha(),
                    null,
                    casResult.jgitResult() != null ? casResult.jgitResult().name() : null,
                    casResult.failureMessage()
            );

            journalService.appendEvent(
                    operationId,
                    fencingToken,
                    RemediationOperationEventType.OPERATION_FAILED,
                    RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                    RemediationOperationStatus.FAILED,
                    op.getTargetRef(),
                    op.getExpectedOldSha(),
                    null,
                    null,
                    "Remediation failed: " + casResult.failureMessage()
            );

            log.warn("Phase C complete: Operation {} FAILED: {}", operationId, casResult.failureMessage());
        }

        return operationRepository.save(op);
    }

    /**
     * Executes the full 4-phase safe remediation lifecycle end-to-end.
     * Enforces repository-level cross-process locking and in-process per-ref locking to prevent
     * race conditions between workers. The repository lock is held continuously across Phase A,
     * Phase B (Git CAS), and Phase C (Durable Result persistence).
     */
    public RemediationOperationEntity executeOperation(UUID operationId, String workerId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(workerId, "workerId cannot be null");

        RemediationOperationEntity op = operationRepository.findById(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        // Global Lock Order Step 1: Acquire exclusive repository lock across OS processes and JVM threads
        try (AutoCloseableLock repoLock = repositoryLockManager.acquireLock(op.getRepositoryPath())) {
            java.util.concurrent.locks.ReentrantLock refLock = processLockManager.getLock(op.getRepositoryPath(), op.getTargetRef());
            refLock.lock();
            try {
                // Re-fetch fresh entity under repository lock and process lock
                op = operationRepository.findById(operationId)
                        .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

                Long currentFencingToken = op.getFencingToken();

                // Phase A: Durable intent in DB Tx 1
                recordDurableIntent(operationId, workerId, currentFencingToken);

                // Phase B: Non-transactional Git CAS outside DB with fencing verification (held under repository lock!)
                GitCasResult casResult = executeGitCas(operationId, currentFencingToken);

                // Phase C: Durable success/failure in DB Tx 2 (held under repository lock!)
                return recordDurableResult(operationId, workerId, currentFencingToken, casResult);
            } finally {
                refLock.unlock();
            }
        }
    }
}
