package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationBreakGlassRequestEntity;
import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.domain.RemediationTargetRefLockEntity;
import com.traceback.core.remediation.git.GitCasAdapter;
import com.traceback.core.repository.RemediationBreakGlassRequestRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.core.repository.RemediationTargetRefLockRepository;
import com.traceback.core.security.RemediationAuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core business service orchestrating the dual-custody break-glass recovery lifecycle.
 * In Stage 4.3, only the KEEP_FROZEN decision is supported.
 * Enforces strict lock hierarchy: OS Repository Lock -> In-Process Ref Lock -> Database Row Locks.
 */
@Service
public class RemediationBreakGlassService {

    private static final Logger log = LoggerFactory.getLogger(RemediationBreakGlassService.class);
    private static final Duration PROPOSAL_EXPIRY_DURATION = Duration.ofMinutes(30);
    private static final Duration REPO_LOCK_TIMEOUT = Duration.ofSeconds(5);

    private final RemediationBreakGlassRequestRepository requestRepository;
    private final RemediationOperationRepository operationRepository;
    private final RemediationTargetRefLockRepository targetRefLockRepository;
    private final RemediationJournalService journalService;
    private final GitCasAdapter gitCasAdapter;
    private final GitRepositoryLockManager repositoryLockManager;
    private final RemediationProcessLockManager processLockManager;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public RemediationBreakGlassService(
            RemediationBreakGlassRequestRepository requestRepository,
            RemediationOperationRepository operationRepository,
            RemediationTargetRefLockRepository targetRefLockRepository,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            @Autowired(required = false) GitRepositoryLockManager repositoryLockManager,
            PlatformTransactionManager transactionManager
    ) {
        this(
                requestRepository,
                operationRepository,
                targetRefLockRepository,
                journalService,
                gitCasAdapter,
                repositoryLockManager != null ? repositoryLockManager : new DefaultGitRepositoryLockManager(),
                new RemediationProcessLockManager(),
                new TransactionTemplate(transactionManager),
                Clock.systemUTC()
        );
    }

    public RemediationBreakGlassService(
            RemediationBreakGlassRequestRepository requestRepository,
            RemediationOperationRepository operationRepository,
            RemediationTargetRefLockRepository targetRefLockRepository,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            GitRepositoryLockManager repositoryLockManager,
            RemediationProcessLockManager processLockManager,
            TransactionTemplate transactionTemplate
    ) {
        this(
                requestRepository,
                operationRepository,
                targetRefLockRepository,
                journalService,
                gitCasAdapter,
                repositoryLockManager,
                processLockManager,
                transactionTemplate,
                Clock.systemUTC()
        );
    }

    public RemediationBreakGlassService(
            RemediationBreakGlassRequestRepository requestRepository,
            RemediationOperationRepository operationRepository,
            RemediationTargetRefLockRepository targetRefLockRepository,
            RemediationJournalService journalService,
            GitCasAdapter gitCasAdapter,
            GitRepositoryLockManager repositoryLockManager,
            RemediationProcessLockManager processLockManager,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.requestRepository = Objects.requireNonNull(requestRepository, "requestRepository cannot be null");
        this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository cannot be null");
        this.targetRefLockRepository = Objects.requireNonNull(targetRefLockRepository, "targetRefLockRepository cannot be null");
        this.journalService = Objects.requireNonNull(journalService, "journalService cannot be null");
        this.gitCasAdapter = Objects.requireNonNull(gitCasAdapter, "gitCasAdapter cannot be null");
        this.repositoryLockManager = Objects.requireNonNull(repositoryLockManager, "repositoryLockManager cannot be null");
        this.processLockManager = Objects.requireNonNull(processLockManager, "processLockManager cannot be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Proposes a new dual-custody break-glass review for an operation in RECOVERY_REQUIRED.
     * Enforces strict lock hierarchy:
     * 1. OS Repository Lock (GitRepositoryLockManager)
     * 2. In-Process Ref Lock (RemediationProcessLockManager)
     * 3. Database Row Locks (findByIdForUpdate / findByRefNameForUpdate)
     */
    public RemediationBreakGlassRequestEntity proposeRequest(
            ProposeBreakGlassCommand command,
            BreakGlassAdminIdentity initiator
    ) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(initiator, "initiator cannot be null");

        // Step 1: Obtain the operation's repository/ref identifiers using a non-locking preliminary lookup
        RemediationOperationEntity preliminaryOp = operationRepository.findById(command.operationId())
                .orElseThrow(() -> new RemediationOperationNotFoundException(command.operationId()));

        String repoPath = preliminaryOp.getRepositoryPath();
        String targetRef = preliminaryOp.getTargetRef();

        // Step 2: Acquire OS repository lock (Tier 1)
        try (AutoCloseableLock repoLock = repositoryLockManager.acquireLock(repoPath, REPO_LOCK_TIMEOUT)) {

            // Step 3: Acquire in-process per-ref serialization lock (Tier 2)
            ReentrantLock refLock = processLockManager.getLock(repoPath, targetRef);
            refLock.lock();
            try {

                // Step 4: Begin database transaction (Tier 3)
                return transactionTemplate.execute(txStatus -> {

                    // Step 5: Fetch and lock operation row
                    RemediationOperationEntity op = operationRepository.findByIdForUpdate(command.operationId())
                            .orElseThrow(() -> new RemediationOperationNotFoundException(command.operationId()));

                    if (!Objects.equals(op.getRepositoryPath(), repoPath) || !Objects.equals(op.getTargetRef(), targetRef)) {
                        log.warn("Operation repo/ref changed between preliminary lookup and lock acquisition for op {}", op.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Operation repository path or target ref changed during lock acquisition for operation " + op.getId()
                        );
                    }

                    // Step 6: Fetch and lock target-ref lock row
                    RemediationTargetRefLockEntity targetRefLock = targetRefLockRepository.findByRefNameForUpdate(op.getTargetRef())
                            .orElseThrow(() -> new IllegalStateException("Target ref lock record not found for: " + op.getTargetRef()));

                    // Step 7: Revalidate operation state, frozen state, and fencing-token consistency
                    if (op.getStatus() != RemediationOperationStatus.RECOVERY_REQUIRED) {
                        log.warn("Rejected break-glass proposal: operation {} is in status {} (expected RECOVERY_REQUIRED)", op.getId(), op.getStatus());
                        throw new RemediationPreconditionConflictException(
                                "Cannot propose break-glass review: operation " + op.getId() + " is in status '" + op.getStatus() + "' (expected RECOVERY_REQUIRED)"
                        );
                    }

                    if (!Boolean.TRUE.equals(targetRefLock.getIsFrozen())) {
                        log.warn("Rejected break-glass proposal: target ref '{}' is not frozen", op.getTargetRef());
                        throw new RemediationPreconditionConflictException("Cannot propose break-glass review: target ref '" + op.getTargetRef() + "' is not frozen");
                    }

                    if (!Objects.equals(op.getFencingToken(), targetRefLock.getCurrentFencingToken())) {
                        log.warn("Rejected break-glass proposal: fencing token mismatch for op {} (op={}, refLock={})",
                                op.getId(), op.getFencingToken(), targetRefLock.getCurrentFencingToken());
                        throw new RemediationPreconditionConflictException(
                                "Cannot propose break-glass review: operation fencing token (" + op.getFencingToken() +
                                        ") does not match ref lock fencing token (" + targetRefLock.getCurrentFencingToken() + ")"
                        );
                    }

                    // Step 8: Resolve physical Git ref SHA while application locks remain held
                    String snapshotGitSha = gitCasAdapter.resolveRefSha(op.getRepositoryPath(), op.getTargetRef())
                            .orElseThrow(() -> new IllegalStateException("Unable to resolve physical Git ref for: " + op.getTargetRef()));

                    Instant now = clock.instant();

                    // Step 9: Expired pending-request lifecycle (lazy expiration)
                    Optional<RemediationBreakGlassRequestEntity> existingPendingOpt =
                            requestRepository.findByOperationIdAndStatus(op.getId(), RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);

                    if (existingPendingOpt.isPresent()) {
                        RemediationBreakGlassRequestEntity existingReq = existingPendingOpt.get();
                        existingReq = requestRepository.findByIdAndOperationIdForUpdate(existingReq.getId(), op.getId())
                                .orElse(existingReq);

                        // If unexpired (now < expiresAt), reject with concurrency conflict
                        if (now.isBefore(existingReq.getExpiresAt())) {
                            log.warn("Rejected break-glass proposal: active unexpired pending request {} exists for op {}",
                                    existingReq.getId(), op.getId());
                            throw new RemediationConcurrencyConflictException(
                                    "An active pending break-glass request already exists for operation " + op.getId()
                            );
                        } else {
                            // If expired (now >= expiresAt), lazily transition to EXPIRED
                            log.info("Active request {} for op {} expired at {}. Lazily transitioning to EXPIRED at {}.",
                                    existingReq.getId(), op.getId(), existingReq.getExpiresAt(), now);
                            existingReq.setStatus(RemediationBreakGlassStatus.EXPIRED);
                            existingReq.setExecutionNotes(null);
                            Instant resolvedAt = now.isBefore(existingReq.getExpiresAt()) ? existingReq.getExpiresAt() : now;
                            existingReq.setResolvedAt(resolvedAt);
                            requestRepository.saveAndFlush(existingReq);

                            journalService.appendEvent(
                                    op.getId(),
                                    op.getFencingToken(),
                                    RemediationOperationEventType.BREAK_GLASS_EXPIRED,
                                    op.getStatus(),
                                    op.getStatus(),
                                    existingReq.getTargetRef(),
                                    existingReq.getExpectedOldSha(),
                                    existingReq.getSnapshotGitSha(),
                                    null,
                                    "Break-glass request " + existingReq.getId() + " expired at " + existingReq.getExpiresAt() +
                                            " (lazily transitioned during new proposal creation)"
                            );
                        }
                    }

                    // Create and persist the new proposal
                    RemediationBreakGlassRequestEntity entity = new RemediationBreakGlassRequestEntity();
                    entity.setId(UUID.randomUUID());
                    entity.setOperationId(op.getId());
                    entity.setRepositoryPath(op.getRepositoryPath());
                    entity.setTargetRef(op.getTargetRef());
                    entity.setExpectedOldSha(op.getExpectedOldSha());
                    entity.setProposedNewSha(op.getProposedNewSha());
                    entity.setSnapshotGitSha(snapshotGitSha);
                    entity.setOperationFencingToken(op.getFencingToken());
                    entity.setRequestedDecision(RemediationBreakGlassDecision.KEEP_FROZEN);
                    entity.setStatus(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
                    entity.setRationale(command.rationale());
                    entity.setEvidenceDigest(command.evidenceDigest());
                    entity.setEvidenceBundleRef(command.evidenceBundleRef());
                    entity.setEvidenceBundleVersion(command.evidenceBundleVersion());
                    entity.setInitiatorIssuer(initiator.issuer());
                    entity.setInitiatorSubject(initiator.subject());
                    entity.setCreatedAt(now);
                    entity.setExpiresAt(now.plus(PROPOSAL_EXPIRY_DURATION));

                    RemediationBreakGlassRequestEntity saved;
                    try {
                        saved = requestRepository.saveAndFlush(entity);
                    } catch (DataIntegrityViolationException e) {
                        if (isUniqueActiveProposalViolation(e)) {
                            log.warn("Concurrent proposal conflict for operation {}: {}", op.getId(), e.getMessage());
                            throw new RemediationConcurrencyConflictException(
                                    "An active pending break-glass request already exists for operation " + op.getId(), e
                            );
                        }
                        throw e;
                    }

                    // Record append-only audit journal event
                    journalService.appendEvent(
                            op.getId(),
                            op.getFencingToken(),
                            RemediationOperationEventType.BREAK_GLASS_PROPOSED,
                            op.getStatus(),
                            op.getStatus(),
                            op.getTargetRef(),
                            op.getExpectedOldSha(),
                            snapshotGitSha,
                            null,
                            "Break-glass proposal created by " + initiator.issuer() + ":" + initiator.subject() +
                                    " (requestId=" + saved.getId() + "). Expires at: " + saved.getExpiresAt()
                    );

                    log.info("Break-glass review proposed: id={}, operationId={}, ref={}, expiresAt={}",
                            saved.getId(), op.getId(), op.getTargetRef(), saved.getExpiresAt());
                    return saved;
                });
            } finally {
                refLock.unlock();
            }
        }
    }

    /**
     * Finalizes a break-glass request with second-administrator KEEP_FROZEN confirmation.
     * Enforces the Global Lock Order:
     * 1. OS Repository Lock (GitRepositoryLockManager)
     * 2. In-Process Ref Lock (RemediationProcessLockManager)
     * 3. Database Row Locks (Strict Monotonic Order: R3 Operation -> R4 Target Ref -> R5 Request)
     */
    public BreakGlassFinalizationOutcome finalizeKeepFrozen(
            FinalizeBreakGlassCommand command,
            BreakGlassAdminIdentity approver
    ) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(approver, "approver cannot be null");

        // Preliminary load without lock to acquire paths for external locking
        RemediationBreakGlassRequestEntity unverified = requestRepository.findById(command.requestId())
                .orElseThrow(() -> new NoSuchElementException("Break-glass request not found: " + command.requestId()));

        if (!unverified.getOperationId().equals(command.operationId())) {
            log.warn("Scoping violation: request {} does not belong to operation {}", command.requestId(), command.operationId());
            throw new IllegalArgumentException(
                    "Break-glass request " + command.requestId() + " does not belong to operation " + command.operationId()
            );
        }

        // Global Lock Order Step 1: Exclusive OS Repository Lock across processes and threads
        try (AutoCloseableLock repoLock = repositoryLockManager.acquireLock(unverified.getRepositoryPath(), REPO_LOCK_TIMEOUT)) {

            // Global Lock Order Step 2: In-Process per-ref serialization lock
            ReentrantLock refLock = processLockManager.getLock(unverified.getRepositoryPath(), unverified.getTargetRef());
            refLock.lock();
            try {

                // Global Lock Order Step 3: Database transaction and row locks (Strict Monotonic Order: R3 -> R4 -> R5)
                return transactionTemplate.execute(txStatus -> {
                    // Step 3a: Lock operation row (R3)
                    RemediationOperationEntity op = operationRepository.findByIdForUpdate(command.operationId())
                            .orElseThrow(() -> new RemediationOperationNotFoundException(command.operationId()));

                    // Revalidate against preliminary values to reject repository/ref drift
                    if (!Objects.equals(op.getRepositoryPath(), unverified.getRepositoryPath()) ||
                            !Objects.equals(op.getTargetRef(), unverified.getTargetRef())) {
                        log.warn("Operation repo/ref drifted during finalization lock acquisition for op {}", op.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Operation repository path or target ref changed during lock acquisition for operation " + op.getId()
                        );
                    }

                    // Step 3b: Lock target-ref row (R4)
                    RemediationTargetRefLockEntity targetRefLock = targetRefLockRepository.findByRefNameForUpdate(op.getTargetRef())
                            .orElseThrow(() -> new IllegalStateException("Target ref lock record not found for: " + op.getTargetRef()));

                    // Step 3c: Lock and scope break-glass request row (R5)
                    RemediationBreakGlassRequestEntity req = requestRepository.findByIdAndOperationIdForUpdate(command.requestId(), command.operationId())
                            .orElseThrow(() -> new NoSuchElementException(
                                    "Break-glass request not found for id " + command.requestId() + " and operation " + command.operationId()
                            ));

                    // Revalidate request fields against locked operation
                    if (!Objects.equals(req.getRepositoryPath(), op.getRepositoryPath()) ||
                            !Objects.equals(req.getTargetRef(), op.getTargetRef())) {
                        log.warn("Request repo/ref drifted from locked operation for request {}", req.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Break-glass request repository path or target ref drifted from locked operation " + op.getId()
                        );
                    }

                    // Verify request is still pending
                    if (req.getStatus() != RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL) {
                        log.warn("Finalization conflict: request {} is already in terminal state {}", req.getId(), req.getStatus());
                        throw new RemediationConcurrencyConflictException(
                                "Break-glass request " + req.getId() + " is already in terminal state: " + req.getStatus()
                        );
                    }

                    // Enforce anti-self-approval
                    if (approver.matches(req.getInitiatorIssuer(), req.getInitiatorSubject())) {
                        log.warn("Anti-self-approval rejected: approver {}:{} matches initiator", approver.issuer(), approver.subject());
                        throw new RemediationAuthorizationException(
                                "Anti-self-approval violation: Approver cannot match proposal initiator (" + approver.issuer() + ":" + approver.subject() + ")"
                        );
                    }

                    Instant now = clock.instant();

                    // Check Expiration branch (inclusive comparison: now >= expiresAt)
                    if (!now.isBefore(req.getExpiresAt())) {
                        log.info("Break-glass request {} has expired (now={}, expiresAt={}). Committing EXPIRED.",
                                req.getId(), now, req.getExpiresAt());
                        req.setStatus(RemediationBreakGlassStatus.EXPIRED);
                        Instant resolvedAt = now.isBefore(req.getExpiresAt()) ? req.getExpiresAt() : now;
                        req.setResolvedAt(resolvedAt);
                        req.setExecutionNotes(null);
                        requestRepository.saveAndFlush(req);

                        journalService.appendEvent(
                                op.getId(),
                                op.getFencingToken(),
                                RemediationOperationEventType.BREAK_GLASS_EXPIRED,
                                op.getStatus(),
                                op.getStatus(),
                                req.getTargetRef(),
                                req.getExpectedOldSha(),
                                req.getSnapshotGitSha(),
                                null,
                                "Break-glass request " + req.getId() + " expired at " + req.getExpiresAt()
                        );

                        return BreakGlassFinalizationOutcome.expired(req, op.getStatus(), targetRefLock.getIsFrozen());
                    }

                    // Check Stale / Invalidated branch
                    Optional<String> currentShaOpt = gitCasAdapter.resolveRefSha(req.getRepositoryPath(), req.getTargetRef());
                    String invalidationReason = null;

                    if (op.getStatus() != RemediationOperationStatus.RECOVERY_REQUIRED) {
                        invalidationReason = "Operation is no longer in RECOVERY_REQUIRED status (found: " + op.getStatus() + ")";
                    } else if (!Boolean.TRUE.equals(targetRefLock.getIsFrozen())) {
                        invalidationReason = "Target ref is no longer frozen";
                    } else if (!op.getFencingToken().equals(req.getOperationFencingToken())) {
                        invalidationReason = "Operation fencing token moved from " + req.getOperationFencingToken() + " to " + op.getFencingToken();
                    } else if (currentShaOpt.isEmpty() || !currentShaOpt.get().equalsIgnoreCase(req.getSnapshotGitSha())) {
                        invalidationReason = "Physical Git ref shifted from snapshot " + req.getSnapshotGitSha() + " to " + currentShaOpt.orElse("ABSENT");
                    }

                    if (invalidationReason != null) {
                        log.warn("Break-glass request {} invalidated: {}. Committing STALE_INVALIDATED.", req.getId(), invalidationReason);
                        req.setStatus(RemediationBreakGlassStatus.STALE_INVALIDATED);
                        req.setResolvedAt(now);
                        req.setExecutionNotes(invalidationReason);
                        requestRepository.saveAndFlush(req);

                        journalService.appendEvent(
                                op.getId(),
                                op.getFencingToken(),
                                RemediationOperationEventType.BREAK_GLASS_INVALIDATED,
                                op.getStatus(),
                                op.getStatus(),
                                req.getTargetRef(),
                                req.getExpectedOldSha(),
                                currentShaOpt.orElse("UNKNOWN"),
                                null,
                                "Break-glass request " + req.getId() + " invalidated: " + invalidationReason
                        );

                        return BreakGlassFinalizationOutcome.staleInvalidated(req, invalidationReason, op.getStatus(), targetRefLock.getIsFrozen());
                    }

                    // Valid second approval: Apply KEEP_FROZEN decision
                    req.setStatus(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);
                    req.setApproverIssuer(approver.issuer());
                    req.setApproverSubject(approver.subject());
                    req.setResolvedAt(now);
                    req.setExecutionNotes(command.executionNotes());
                    requestRepository.saveAndFlush(req);

                    // Operation remains in RECOVERY_REQUIRED
                    op.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
                    op.setRecoveryStatus("BREAK_GLASS_FREEZE_RETAINED");
                    operationRepository.saveAndFlush(op);

                    // Target ref lock remains FROZEN
                    targetRefLock.setIsFrozen(true);
                    targetRefLock.setFrozenReason("Freeze confirmed by dual-custody review (requestId=" + req.getId() + ")");
                    targetRefLockRepository.saveAndFlush(targetRefLock);

                    journalService.appendEvent(
                            op.getId(),
                            op.getFencingToken(),
                            RemediationOperationEventType.BREAK_GLASS_FREEZE_RETAINED,
                            op.getStatus(),
                            op.getStatus(),
                            req.getTargetRef(),
                            req.getExpectedOldSha(),
                            req.getSnapshotGitSha(),
                            null,
                            "Dual-custody review by " + approver.issuer() + ":" + approver.subject() +
                                    " confirmed freeze retention for request " + req.getId() +
                                    (command.executionNotes() != null ? ". Notes: " + command.executionNotes() : "")
                    );

                    log.info("Break-glass request {} finalized as EXECUTED_KEPT_FROZEN by {}:{}",
                            req.getId(), approver.issuer(), approver.subject());

                    return BreakGlassFinalizationOutcome.executedKeptFrozen(req, op.getStatus(), targetRefLock.getIsFrozen());
                });
            } finally {
                refLock.unlock();
            }
        }
    }

    /**
     * Rejects a pending break-glass request by a second administrator.
     * Enforces the Global Lock Order:
     * 1. OS Repository Lock (GitRepositoryLockManager)
     * 2. In-Process Ref Lock (RemediationProcessLockManager)
     * 3. Database Row Locks (Strict Monotonic Order: R3 Operation -> R5 Request)
     */
    public RemediationBreakGlassRequestEntity rejectRequest(
            RejectBreakGlassCommand command,
            BreakGlassAdminIdentity approver
    ) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(approver, "approver cannot be null");

        // Preliminary load without lock to acquire paths for external locking
        RemediationBreakGlassRequestEntity unverified = requestRepository.findById(command.requestId())
                .orElseThrow(() -> new NoSuchElementException("Break-glass request not found: " + command.requestId()));

        if (!unverified.getOperationId().equals(command.operationId())) {
            log.warn("Scoping violation: request {} does not belong to operation {}", command.requestId(), command.operationId());
            throw new IllegalArgumentException(
                    "Break-glass request " + command.requestId() + " does not belong to operation " + command.operationId()
            );
        }

        // Global Lock Order Step 1: Exclusive OS Repository Lock across processes and threads
        try (AutoCloseableLock repoLock = repositoryLockManager.acquireLock(unverified.getRepositoryPath(), REPO_LOCK_TIMEOUT)) {

            // Global Lock Order Step 2: In-Process per-ref serialization lock
            ReentrantLock refLock = processLockManager.getLock(unverified.getRepositoryPath(), unverified.getTargetRef());
            refLock.lock();
            try {

                // Global Lock Order Step 3: Database transaction and row locks (Strict Monotonic Order: R3 -> R5)
                return transactionTemplate.execute(txStatus -> {
                    // Step 3a: Lock operation row (R3)
                    RemediationOperationEntity op = operationRepository.findByIdForUpdate(command.operationId())
                            .orElseThrow(() -> new RemediationOperationNotFoundException(command.operationId()));

                    // Revalidate against preliminary values to reject repository/ref drift
                    if (!Objects.equals(op.getRepositoryPath(), unverified.getRepositoryPath()) ||
                            !Objects.equals(op.getTargetRef(), unverified.getTargetRef())) {
                        log.warn("Operation repo/ref drifted during rejection lock acquisition for op {}", op.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Operation repository path or target ref changed during lock acquisition for operation " + op.getId()
                        );
                    }

                    // Step 3b: Lock and scope break-glass request row (R5)
                    RemediationBreakGlassRequestEntity req = requestRepository.findByIdAndOperationIdForUpdate(command.requestId(), command.operationId())
                            .orElseThrow(() -> new NoSuchElementException(
                                    "Break-glass request not found for id " + command.requestId() + " and operation " + command.operationId()
                            ));

                    // Revalidate request fields against locked operation
                    if (!Objects.equals(req.getRepositoryPath(), op.getRepositoryPath()) ||
                            !Objects.equals(req.getTargetRef(), op.getTargetRef())) {
                        log.warn("Request repo/ref drifted from locked operation for request {}", req.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Break-glass request repository path or target ref drifted from locked operation " + op.getId()
                        );
                    }

                    if (req.getStatus() != RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL) {
                        log.warn("Rejection conflict: request {} is already in terminal state {}", req.getId(), req.getStatus());
                        throw new RemediationConcurrencyConflictException(
                                "Break-glass request " + req.getId() + " is already in terminal state: " + req.getStatus()
                        );
                    }

                    if (approver.matches(req.getInitiatorIssuer(), req.getInitiatorSubject())) {
                        log.warn("Anti-self-approval rejected: rejecter {}:{} matches initiator", approver.issuer(), approver.subject());
                        throw new RemediationAuthorizationException(
                                "Anti-self-approval violation: Rejecting administrator cannot match proposal initiator"
                        );
                    }

                    Instant now = clock.instant();
                    req.setStatus(RemediationBreakGlassStatus.REJECTED);
                    req.setApproverIssuer(approver.issuer());
                    req.setApproverSubject(approver.subject());
                    Instant resolvedAt = now.isBefore(req.getCreatedAt()) ? req.getCreatedAt() : now;
                    req.setResolvedAt(resolvedAt);
                    req.setRejectionReason(command.rejectionReason());
                    req.setExecutionNotes(null);

                    RemediationBreakGlassRequestEntity saved = requestRepository.saveAndFlush(req);

                    journalService.appendEvent(
                            op.getId(),
                            op.getFencingToken(),
                            RemediationOperationEventType.BREAK_GLASS_REJECTED,
                            op.getStatus(),
                            op.getStatus(),
                            req.getTargetRef(),
                            req.getExpectedOldSha(),
                            req.getSnapshotGitSha(),
                            null,
                            "Break-glass request " + req.getId() + " rejected by " + approver.issuer() + ":" + approver.subject() +
                                    ". Reason: " + command.rejectionReason()
                    );

                    log.info("Break-glass request {} rejected by {}:{}", req.getId(), approver.issuer(), approver.subject());
                    return saved;
                });
            } finally {
                refLock.unlock();
            }
        }
    }

    /**
     * Cancels a pending break-glass request by the original proposing administrator.
     * Enforces the Global Lock Order:
     * 1. OS Repository Lock (GitRepositoryLockManager)
     * 2. In-Process Ref Lock (RemediationProcessLockManager)
     * 3. Database Row Locks (Strict Monotonic Order: R3 Operation -> R5 Request)
     */
    public RemediationBreakGlassRequestEntity cancelRequest(
            CancelBreakGlassCommand command,
            BreakGlassAdminIdentity canceller
    ) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(canceller, "canceller cannot be null");

        // Preliminary load without lock to acquire paths for external locking
        RemediationBreakGlassRequestEntity unverified = requestRepository.findById(command.requestId())
                .orElseThrow(() -> new NoSuchElementException("Break-glass request not found: " + command.requestId()));

        if (!unverified.getOperationId().equals(command.operationId())) {
            log.warn("Scoping violation: request {} does not belong to operation {}", command.requestId(), command.operationId());
            throw new IllegalArgumentException(
                    "Break-glass request " + command.requestId() + " does not belong to operation " + command.operationId()
            );
        }

        // Global Lock Order Step 1: Exclusive OS Repository Lock across processes and threads
        try (AutoCloseableLock repoLock = repositoryLockManager.acquireLock(unverified.getRepositoryPath(), REPO_LOCK_TIMEOUT)) {

            // Global Lock Order Step 2: In-Process per-ref serialization lock
            ReentrantLock refLock = processLockManager.getLock(unverified.getRepositoryPath(), unverified.getTargetRef());
            refLock.lock();
            try {

                // Global Lock Order Step 3: Database transaction and row locks (Strict Monotonic Order: R3 -> R5)
                return transactionTemplate.execute(txStatus -> {
                    // Step 3a: Lock operation row (R3)
                    RemediationOperationEntity op = operationRepository.findByIdForUpdate(command.operationId())
                            .orElseThrow(() -> new RemediationOperationNotFoundException(command.operationId()));

                    // Revalidate against preliminary values to reject repository/ref drift
                    if (!Objects.equals(op.getRepositoryPath(), unverified.getRepositoryPath()) ||
                            !Objects.equals(op.getTargetRef(), unverified.getTargetRef())) {
                        log.warn("Operation repo/ref drifted during cancellation lock acquisition for op {}", op.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Operation repository path or target ref changed during lock acquisition for operation " + op.getId()
                        );
                    }

                    // Step 3b: Lock and scope break-glass request row (R5)
                    RemediationBreakGlassRequestEntity req = requestRepository.findByIdAndOperationIdForUpdate(command.requestId(), command.operationId())
                            .orElseThrow(() -> new NoSuchElementException(
                                    "Break-glass request not found for id " + command.requestId() + " and operation " + command.operationId()
                            ));

                    // Revalidate request fields against locked operation
                    if (!Objects.equals(req.getRepositoryPath(), op.getRepositoryPath()) ||
                            !Objects.equals(req.getTargetRef(), op.getTargetRef())) {
                        log.warn("Request repo/ref drifted from locked operation for request {}", req.getId());
                        throw new RemediationConcurrencyConflictException(
                                "Break-glass request repository path or target ref drifted from locked operation " + op.getId()
                        );
                    }

                    if (req.getStatus() != RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL) {
                        log.warn("Cancellation conflict: request {} is already in terminal state {}", req.getId(), req.getStatus());
                        throw new RemediationConcurrencyConflictException(
                                "Break-glass request " + req.getId() + " is already in terminal state: " + req.getStatus()
                        );
                    }

                    // Only the original initiator may cancel
                    if (!canceller.matches(req.getInitiatorIssuer(), req.getInitiatorSubject())) {
                        log.warn("Unauthorized cancellation attempt for request {}: caller was {}:{}, expected initiator {}:{}",
                                req.getId(), canceller.issuer(), canceller.subject(), req.getInitiatorIssuer(), req.getInitiatorSubject());
                        throw new RemediationAuthorizationException(
                                "Only the proposing administrator (" + req.getInitiatorIssuer() + ":" + req.getInitiatorSubject() +
                                        ") may cancel this break-glass request. Caller was: " + canceller.issuer() + ":" + canceller.subject()
                        );
                    }

                    Instant now = clock.instant();
                    req.setStatus(RemediationBreakGlassStatus.CANCELLED);
                    req.setCancelledByIssuer(canceller.issuer());
                    req.setCancelledBySubject(canceller.subject());
                    Instant cancelledAt = now.isBefore(req.getCreatedAt()) ? req.getCreatedAt() : now;
                    req.setCancelledAt(cancelledAt);
                    req.setCancellationReason(command.cancellationReason());

                    RemediationBreakGlassRequestEntity saved = requestRepository.saveAndFlush(req);

                    journalService.appendEvent(
                            op.getId(),
                            op.getFencingToken(),
                            RemediationOperationEventType.BREAK_GLASS_CANCELLED,
                            op.getStatus(),
                            op.getStatus(),
                            req.getTargetRef(),
                            req.getExpectedOldSha(),
                            req.getSnapshotGitSha(),
                            null,
                            "Break-glass request " + req.getId() + " cancelled by initiator " + canceller.issuer() + ":" + canceller.subject() +
                                    ". Reason: " + command.cancellationReason()
                    );

                    log.info("Break-glass request {} cancelled by initiator {}:{}", req.getId(), canceller.issuer(), canceller.subject());
                    return saved;
                });
            } finally {
                refLock.unlock();
            }
        }
    }

    private boolean isUniqueActiveProposalViolation(DataIntegrityViolationException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String constraintName = cve.getConstraintName();
                if (constraintName != null && constraintName.toLowerCase().contains("uq_active_break_glass_op")) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("uq_active_break_glass_op")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Retrieves a break-glass request by operation and request ID.
     */
    public Optional<RemediationBreakGlassRequestEntity> getRequest(UUID operationId, UUID requestId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(requestId, "requestId cannot be null");
        return requestRepository.findById(requestId)
                .filter(r -> r.getOperationId().equals(operationId));
    }

    /**
     * Finds the active pending request for an operation, if one exists.
     */
    public Optional<RemediationBreakGlassRequestEntity> getActiveRequest(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        return requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
    }

    /**
     * Lists all break-glass requests for an operation in descending chronological order.
     */
    public List<RemediationBreakGlassRequestEntity> getRequestsForOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        return requestRepository.findByOperationIdOrderByCreatedAtDesc(operationId);
    }
}
