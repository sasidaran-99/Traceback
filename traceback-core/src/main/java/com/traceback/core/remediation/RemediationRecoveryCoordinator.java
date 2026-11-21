package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.remediation.git.GitCasAdapter;
import com.traceback.core.repository.RemediationOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recovers interrupted or timed-out remediation operations following worker crashes.
 * Enforces fail-closed attribution invariants:
 * - A matching Git SHA alone is never proof of worker execution.
 * - If post-CAS durable success is absent, recovery fails closed, freezes the target ref, and escalates.
 */
@Service
public class RemediationRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RemediationRecoveryCoordinator.class);
    private static final long STALE_PROPOSAL_TIMEOUT_SECONDS = 3600L;

    private final RemediationOperationRepository operationRepository;
    private final RemediationLockCoordinator lockCoordinator;
    private final RemediationJournalService journalService;
    private final GitCasAdapter gitCasAdapter;
    private final RemediationProcessLockManager processLockManager;
    private final GitRepositoryLockManager repositoryLockManager;

    @Autowired
    public RemediationRecoveryCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            @Autowired(required = false) GitRepositoryLockManager repositoryLockManager
    ) {
        this(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                new RemediationProcessLockManager(),
                repositoryLockManager != null ? repositoryLockManager : new DefaultGitRepositoryLockManager()
        );
    }

    public RemediationRecoveryCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter
    ) {
        this(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                new RemediationProcessLockManager(),
                new DefaultGitRepositoryLockManager()
        );
    }

    public RemediationRecoveryCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            RemediationProcessLockManager processLockManager
    ) {
        this(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                processLockManager,
                new DefaultGitRepositoryLockManager()
        );
    }

    public RemediationRecoveryCoordinator(
            RemediationOperationRepository operationRepository,
            RemediationLockCoordinator lockCoordinator,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            RemediationProcessLockManager processLockManager,
            GitRepositoryLockManager repositoryLockManager
    ) {
        this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository cannot be null");
        this.lockCoordinator = Objects.requireNonNull(lockCoordinator, "lockCoordinator cannot be null");
        this.journalService = Objects.requireNonNull(journalService, "journalService cannot be null");
        this.gitCasAdapter = Objects.requireNonNull(gitCasAdapter, "gitCasAdapter cannot be null");
        this.processLockManager = processLockManager != null ? processLockManager : new RemediationProcessLockManager();
        this.repositoryLockManager = repositoryLockManager != null ? repositoryLockManager : new DefaultGitRepositoryLockManager();
    }

    /**
     * Scans for operations with expired worker leases and triggers safe recovery.
     * Also sweeps for stale CREATED proposals and cancels them.
     */
    @Transactional
    public int recoverExpiredOperations() {
        Instant now = Instant.now();
        int count = 0;

        List<RemediationOperationEntity> expired = operationRepository.findExpiredLeaseOperations(
                List.of(RemediationOperationStatus.PRE_GIT_CAS_INTENT),
                now
        );

        for (RemediationOperationEntity op : expired) {
            try {
                auditAndRecoverOperation(op.getId());
                count++;
            } catch (Exception e) {
                log.error("Failed to recover operation {}: {}", op.getId(), e.getMessage(), e);
            }
        }

        List<RemediationOperationEntity> staleProposals = operationRepository.findStaleProposals(
                RemediationOperationStatus.CREATED,
                now.minusSeconds(STALE_PROPOSAL_TIMEOUT_SECONDS)
        );

        for (RemediationOperationEntity op : staleProposals) {
            try {
                cancelStaleProposal(op.getId());
                count++;
            } catch (Exception e) {
                log.error("Failed to cancel stale proposal {}: {}", op.getId(), e.getMessage(), e);
            }
        }

        return count;
    }

    /**
     * Safely cancels a stale CREATED proposal that expired before worker lease acquisition.
     */
    @Transactional
    public RemediationOperationEntity cancelStaleProposal(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        RemediationOperationEntity op = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        if (op.getStatus() == RemediationOperationStatus.CREATED) {
            op.setStatus(RemediationOperationStatus.CANCELLED);
            op.setRecoveryStatus("STALE_PROPOSAL_CANCELLED");
            RemediationOperationEntity saved = operationRepository.save(op);

            journalService.appendEvent(
                    operationId,
                    op.getFencingToken(),
                    RemediationOperationEventType.OPERATION_CANCELLED,
                    RemediationOperationStatus.CREATED,
                    RemediationOperationStatus.CANCELLED,
                    op.getTargetRef(),
                    op.getExpectedOldSha(),
                    op.getProposedNewSha(),
                    null,
                    "Proposal cancelled: expired after " + STALE_PROPOSAL_TIMEOUT_SECONDS + " seconds without execution"
            );
            log.info("Cancelled stale proposal {}", operationId);
            return saved;
        }
        return op;
    }

    /**
     * Audits and recovers a specific interrupted remediation operation.
     * Enforces the Global Lock Order:
     * 1. Repository Lock (GitRepositoryLockManager) acquired across OS processes and JVM threads.
     * 2. Database Row Locks (remediation_target_ref_locks / remediation_operations).
     *
     * Never holds a database row lock while waiting for the repository lock, preventing deadlock.
     * If lock acquisition times out or fails, fails closed into RECOVERY_REQUIRED without classifying as FAILED.
     */
    public RemediationOperationEntity auditAndRecoverOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");

        RemediationOperationEntity initial = operationRepository.findById(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        if (initial.getStatus().isTerminal()) {
            log.info("Operation {} is already in terminal state '{}'. No recovery needed.", operationId, initial.getStatus());
            return initial;
        }

        if (initial.getStatus() == RemediationOperationStatus.CREATED) {
            return cancelStaleProposal(operationId);
        }

        if (initial.getStatus() == RemediationOperationStatus.RECOVERY_REQUIRED) {
            log.warn("Operation {} is already escalated to RECOVERY_REQUIRED. Preserving state.", operationId);
            return initial;
        }

        // Fail-closed in-process check: Disable takeover if active worker holds process lock
        if (processLockManager.isLocked(initial.getRepositoryPath(), initial.getTargetRef())) {
            log.warn("Cannot recover operation {}: Active worker thread is executing on ref '{}'", operationId, initial.getTargetRef());
            throw new IllegalStateException("Cannot recover operation " + operationId + ": worker is actively executing on ref " + initial.getTargetRef());
        }

        // Global Lock Order Step 1: Acquire exclusive repository lock BEFORE starting database write transaction
        AutoCloseableLock repoLock;
        try {
            repoLock = repositoryLockManager.acquireLock(initial.getRepositoryPath(), Duration.ofSeconds(2));
        } catch (GitRepositoryLockException e) {
            log.warn("Recovery failed to acquire repository lock for operation {} on '{}': {}. Escalating to RECOVERY_REQUIRED.",
                    operationId, initial.getRepositoryPath(), e.getMessage());
            // Invariant: If lock acquisition times out or fails, do not mark FAILED. Freeze ref and escalate.
            return escalateUncertainOperation(operationId, "REPOSITORY_LOCK_UNAVAILABLE: " + e.getMessage());
        }

        try (repoLock) {
            return executeTransactionalRecovery(operationId);
        }
    }

    /**
     * Executes recovery audit within an isolated database transaction, with the repository lock held.
     */
    @Transactional
    public RemediationOperationEntity executeTransactionalRecovery(UUID operationId) {
        RemediationOperationEntity op = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        if (op.getStatus().isTerminal()) {
            log.info("Operation {} reached terminal state '{}' before recovery lock acquisition. No recovery needed.", operationId, op.getStatus());
            return op;
        }

        // Level 0 lock: Exclusive lock during recovery to prevent concurrent promoters
        lockCoordinator.acquireTargetRefExclusive(op.getTargetRef());

        // Increment fencing token to fence out the slow or zombie original worker
        Long newFencingToken = op.getFencingToken() + 1L;
        op.setFencingToken(newFencingToken);

        if (op.getStatus() == RemediationOperationStatus.PRE_GIT_CAS_INTENT) {
            boolean hasPostSuccess = journalService.hasCommittedEvent(operationId, RemediationOperationEventType.GIT_CAS_SUCCEEDED);

            if (hasPostSuccess) {
                // Worker successfully executed Git CAS and committed post-success, but crashed before finalization
                op.setStatus(RemediationOperationStatus.PERSISTED);
                op.setLeaseExpiresAt(null);
                op.setRecoveryStatus("RECOVERED_FROM_COMMITTED_SUCCESS");

                journalService.appendEvent(
                        operationId,
                        newFencingToken,
                        RemediationOperationEventType.RECOVERY_COMPLETED,
                        RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                        RemediationOperationStatus.PERSISTED,
                        op.getTargetRef(),
                        op.getExpectedOldSha(),
                        op.getProposedNewSha(),
                        null,
                        "Recovered: Found committed GIT_CAS_SUCCEEDED journal entry. Finalized as PERSISTED."
                );

                log.info("Recovery complete: Operation {} finalized to PERSISTED from committed journal record", operationId);
            } else {
                // Post-CAS success is absent! Inspect physical Git state while holding repository lock.
                Optional<String> currentShaOpt = gitCasAdapter.resolveRefSha(op.getRepositoryPath(), op.getTargetRef());

                if (currentShaOpt.isPresent() && currentShaOpt.get().equalsIgnoreCase(op.getExpectedOldSha())) {
                    // Ref is still at expected old SHA. Git CAS never occurred.
                    op.setStatus(RemediationOperationStatus.FAILED);
                    op.setLeaseExpiresAt(null);
                    op.setFailureClassification(RemediationFailureClassification.SYSTEM_CRASH_OR_TIMEOUT);
                    op.setFailureDetails("Worker lease expired before Git CAS execution. Safely aborted.");
                    op.setRecoveryStatus("SAFELY_ABORTED_PRE_CAS");

                    journalService.appendEvent(
                            operationId,
                            newFencingToken,
                            RemediationOperationEventType.RECOVERY_COMPLETED,
                            RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                            RemediationOperationStatus.FAILED,
                            op.getTargetRef(),
                            op.getExpectedOldSha(),
                            currentShaOpt.get(),
                            null,
                            "Recovered: Target ref remains at expected old SHA. Aborted cleanly."
                    );

                    log.info("Recovery complete: Operation {} aborted cleanly because Git ref was unmutated", operationId);
                } else if (currentShaOpt.isPresent() && currentShaOpt.get().equalsIgnoreCase(op.getProposedNewSha())) {
                    // Ref matches proposed new SHA, but post-success journal entry is ABSENT!
                    // Invariant: Matching SHA alone is NOT proof of worker attribution.
                    // Must fail closed and freeze target ref.
                    op.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
                    op.setLeaseExpiresAt(null);
                    op.setFailureClassification(RemediationFailureClassification.SYSTEM_CRASH_OR_TIMEOUT);
                    op.setFailureDetails("Git ref matches target SHA but durable post-CAS confirmation is absent. Ambiguous attribution.");
                    op.setRecoveryStatus("AMBIGUOUS_EXECUTION_ESCALATED");

                    lockCoordinator.freezeTargetRef(
                            op.getTargetRef(),
                            "Ambiguous Git ref advancement detected during recovery for operation " + operationId
                    );

                    journalService.appendEvent(
                            operationId,
                            newFencingToken,
                            RemediationOperationEventType.RECOVERY_INITIATED,
                            RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                            RemediationOperationStatus.RECOVERY_REQUIRED,
                            op.getTargetRef(),
                            op.getExpectedOldSha(),
                            currentShaOpt.get(),
                            null,
                            "Recovery escalated: Target ref advanced to target SHA without durable post-CAS event. Target ref FROZEN."
                    );

                    log.warn("Recovery failed closed: Operation {} escalated to RECOVERY_REQUIRED and ref '{}' frozen due to ambiguous attribution",
                            operationId, op.getTargetRef());
                } else {
                    // Unexpected ref state
                    String actualSha = currentShaOpt.orElse("REF_ABSENT");
                    op.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
                    op.setLeaseExpiresAt(null);
                    op.setFailureClassification(RemediationFailureClassification.SYSTEM_CRASH_OR_TIMEOUT);
                    op.setFailureDetails("Unexpected Git ref state encountered during recovery: " + actualSha);
                    op.setRecoveryStatus("UNEXPECTED_GIT_STATE_ESCALATED");

                    lockCoordinator.freezeTargetRef(
                            op.getTargetRef(),
                            "Unexpected Git ref SHA encountered during recovery for operation " + operationId + ": " + actualSha
                    );

                    journalService.appendEvent(
                            operationId,
                            newFencingToken,
                            RemediationOperationEventType.RECOVERY_INITIATED,
                            RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                            RemediationOperationStatus.RECOVERY_REQUIRED,
                            op.getTargetRef(),
                            op.getExpectedOldSha(),
                            actualSha,
                            null,
                            "Recovery escalated: Target ref at unexpected SHA " + actualSha + ". Target ref FROZEN."
                    );

                    log.error("Recovery failed closed: Operation {} escalated to RECOVERY_REQUIRED. Ref is at unexpected SHA: {}",
                            operationId, actualSha);
                }
            }
        }

        return operationRepository.save(op);
    }

    /**
     * Escalates an operation to RECOVERY_REQUIRED and freezes the target ref when repository lock
     * acquisition times out or fails, guaranteeing fail-closed safety.
     */
    @Transactional
    public RemediationOperationEntity escalateUncertainOperation(UUID operationId, String reason) {
        RemediationOperationEntity op = operationRepository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new RemediationOperationNotFoundException(operationId));

        if (op.getStatus().isTerminal()) {
            return op;
        }

        lockCoordinator.acquireTargetRefExclusive(op.getTargetRef());

        Long newFencingToken = op.getFencingToken() + 1L;
        op.setFencingToken(newFencingToken);
        op.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
        op.setLeaseExpiresAt(null);
        op.setFailureClassification(RemediationFailureClassification.SYSTEM_CRASH_OR_TIMEOUT);
        op.setFailureDetails(reason);
        op.setRecoveryStatus("LOCK_ACQUISITION_TIMEOUT_ESCALATED");

        lockCoordinator.freezeTargetRef(
                op.getTargetRef(),
                "Target ref frozen due to uncertain recovery lock state: " + reason
        );

        journalService.appendEvent(
                operationId,
                newFencingToken,
                RemediationOperationEventType.RECOVERY_INITIATED,
                op.getStatus(),
                RemediationOperationStatus.RECOVERY_REQUIRED,
                op.getTargetRef(),
                op.getExpectedOldSha(),
                null,
                null,
                "Recovery escalated: Repository lock unavailable. Target ref FROZEN: " + reason
        );

        return operationRepository.save(op);
    }
}
