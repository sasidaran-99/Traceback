package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationBreakGlassRequestEntity;
import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.domain.RemediationTargetRefLockEntity;
import com.traceback.core.remediation.git.GitCasAdapter;
import com.traceback.core.repository.RemediationBreakGlassRequestRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.core.repository.RemediationTargetRefLockRepository;
import com.traceback.core.security.RemediationAuthorizationException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemediationBreakGlassServiceTest {

    private static final String REPO_PATH = "C:/repos/test-app";
    private static final String TARGET_REF = "refs/heads/main";
    private static final String OLD_SHA = "1111111111111111111111111111111111111111";
    private static final String NEW_SHA = "2222222222222222222222222222222222222222";
    private static final String CURRENT_SHA = "1111111111111111111111111111111111111111";
    private static final Long FENCING_TOKEN = 10L;

    private static final BreakGlassAdminIdentity ADMIN_ALICE = new BreakGlassAdminIdentity("https://auth.traceback.internal", "alice-admin");
    private static final BreakGlassAdminIdentity ADMIN_BOB = new BreakGlassAdminIdentity("https://auth.traceback.internal", "bob-admin");

    private RemediationBreakGlassRequestRepository requestRepository;
    private RemediationOperationRepository operationRepository;
    private RemediationTargetRefLockRepository targetRefLockRepository;
    private RemediationJournalService journalService;
    private GitCasAdapter gitCasAdapter;
    private GitRepositoryLockManager repositoryLockManager;
    private RemediationProcessLockManager processLockManager;
    private TransactionTemplate transactionTemplate;

    private RemediationBreakGlassService service;

    private UUID operationId;
    private RemediationOperationEntity operation;
    private RemediationTargetRefLockEntity refLock;

    @BeforeEach
    void setUp() {
        requestRepository = mock(RemediationBreakGlassRequestRepository.class);
        operationRepository = mock(RemediationOperationRepository.class);
        targetRefLockRepository = mock(RemediationTargetRefLockRepository.class);
        journalService = mock(RemediationJournalService.class);
        gitCasAdapter = mock(GitCasAdapter.class);
        repositoryLockManager = mock(GitRepositoryLockManager.class);
        processLockManager = new RemediationProcessLockManager();

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        transactionTemplate = new TransactionTemplate(txManager);

        service = new RemediationBreakGlassService(
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

        operationId = UUID.randomUUID();
        operation = new RemediationOperationEntity();
        operation.setId(operationId);
        operation.setRepositoryPath(REPO_PATH);
        operation.setTargetRef(TARGET_REF);
        operation.setExpectedOldSha(OLD_SHA);
        operation.setProposedNewSha(NEW_SHA);
        operation.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
        operation.setFencingToken(FENCING_TOKEN);
        operation.setInitiatedBy("system");

        refLock = new RemediationTargetRefLockEntity(TARGET_REF);
        refLock.setIsFrozen(true);
        refLock.setFrozenReason("Failed remediation in progress");
        refLock.setCurrentFencingToken(FENCING_TOKEN);

        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));
        when(targetRefLockRepository.findById(TARGET_REF)).thenReturn(Optional.of(refLock));
        when(targetRefLockRepository.findByRefNameForUpdate(TARGET_REF)).thenReturn(Optional.of(refLock));
        when(targetRefLockRepository.findByRefNameForUpdate(anyString())).thenAnswer(inv -> Optional.of(refLock));
        when(gitCasAdapter.resolveRefSha(REPO_PATH, TARGET_REF)).thenReturn(Optional.of(CURRENT_SHA));

        // Default repository lock behavior
        AutoCloseableLock defaultMockLock = mock(AutoCloseableLock.class);
        when(repositoryLockManager.acquireLock(anyString(), any(Duration.class)))
                .thenReturn(defaultMockLock);
    }

    // =========================================================================
    // 1. Proposal Creation & Snapshot Consistency Tests
    // =========================================================================

    @Test
    @DisplayName("proposeRequest creates pending request under full lock hierarchy and appends BREAK_GLASS_PROPOSED")
    void testProposeRequest_Success() {
        when(requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL))
                .thenReturn(Optional.empty());
        when(requestRepository.saveAndFlush(any(RemediationBreakGlassRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(
                operationId,
                "Need freeze review due to unexpected CAS failure",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "bundle-ref-123",
                "v1"
        );

        RemediationBreakGlassRequestEntity result = service.proposeRequest(command, ADMIN_ALICE);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getOperationId()).isEqualTo(operationId);
        assertThat(result.getRepositoryPath()).isEqualTo(REPO_PATH);
        assertThat(result.getTargetRef()).isEqualTo(TARGET_REF);
        assertThat(result.getExpectedOldSha()).isEqualTo(OLD_SHA);
        assertThat(result.getProposedNewSha()).isEqualTo(NEW_SHA);
        assertThat(result.getSnapshotGitSha()).isEqualTo(CURRENT_SHA);
        assertThat(result.getOperationFencingToken()).isEqualTo(FENCING_TOKEN);
        assertThat(result.getRequestedDecision()).isEqualTo(RemediationBreakGlassDecision.KEEP_FROZEN);
        assertThat(result.getStatus()).isEqualTo(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
        assertThat(result.getInitiatorIssuer()).isEqualTo(ADMIN_ALICE.issuer());
        assertThat(result.getInitiatorSubject()).isEqualTo(ADMIN_ALICE.subject());
        assertThat(result.getRationale()).isEqualTo("Need freeze review due to unexpected CAS failure");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getExpiresAt()).isAfter(result.getCreatedAt());

        // Verify lock hierarchy: OS repo lock acquired and target ref lock acquired FOR UPDATE
        verify(repositoryLockManager).acquireLock(eq(REPO_PATH), any(Duration.class));
        verify(operationRepository).findByIdForUpdate(operationId);
        verify(targetRefLockRepository).findByRefNameForUpdate(TARGET_REF);

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_PROPOSED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("proposeRequest throws RemediationConcurrencyConflictException when active unexpired request exists")
    void testProposeRequest_ActiveUnexpiredRequestExists() {
        UUID existingId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity existingReq = createPendingEntity(existingId, operationId);
        // Expiry 20 minutes in the future
        existingReq.setExpiresAt(Instant.now().plus(Duration.ofMinutes(20)));

        when(requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL))
                .thenReturn(Optional.of(existingReq));
        when(requestRepository.findByIdAndOperationIdForUpdate(existingId, operationId))
                .thenReturn(Optional.of(existingReq));

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Duplicate attempt", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("active pending break-glass request already exists");

        verify(requestRepository, never()).saveAndFlush(any());
        verify(journalService, never()).appendEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("proposeRequest lazily expires past pending request and successfully creates new proposal")
    void testProposeRequest_ExpiredPendingRequest_AllowsNewProposal() {
        UUID expiredId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity expiredReq = createPendingEntity(expiredId, operationId);
        // Expiry 10 minutes in the past
        Instant expiredAt = Instant.now().minus(Duration.ofMinutes(10));
        expiredReq.setExpiresAt(expiredAt);

        when(requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL))
                .thenReturn(Optional.of(expiredReq));
        when(requestRepository.findByIdAndOperationIdForUpdate(expiredId, operationId))
                .thenReturn(Optional.of(expiredReq));
        when(requestRepository.saveAndFlush(any(RemediationBreakGlassRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(
                operationId, "New proposal after old one expired", null, null, null
        );

        RemediationBreakGlassRequestEntity result = service.proposeRequest(command, ADMIN_ALICE);

        // Verify the old request was lazily transitioned to EXPIRED
        assertThat(expiredReq.getStatus()).isEqualTo(RemediationBreakGlassStatus.EXPIRED);
        assertThat(expiredReq.getExecutionNotes()).isNull();
        assertThat(expiredReq.getResolvedAt()).isNotNull();
        assertThat(expiredReq.getResolvedAt()).isAfterOrEqualTo(expiredAt);

        // Verify the new request was created in PENDING_SECOND_APPROVAL
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotEqualTo(expiredId);
        assertThat(result.getStatus()).isEqualTo(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);

        // Verify both journal events were appended: BREAK_GLASS_EXPIRED and BREAK_GLASS_PROPOSED
        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_EXPIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_PROPOSED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("proposeRequest rejects when repo or target ref shifted during lock acquisition")
    void testProposeRequest_RepoOrRefShiftDuringLockAcquisition() {
        // Locked op returns different target ref than preliminary lookup
        RemediationOperationEntity shiftedOp = new RemediationOperationEntity();
        shiftedOp.setId(operationId);
        shiftedOp.setRepositoryPath(REPO_PATH);
        shiftedOp.setTargetRef("refs/heads/shifted");
        shiftedOp.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
        shiftedOp.setFencingToken(FENCING_TOKEN);

        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(shiftedOp));

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("changed during lock acquisition");
    }

    @Test
    @DisplayName("proposeRequest rejects when operation fencing token does not match ref lock fencing token")
    void testProposeRequest_FencingTokenMismatch() {
        refLock.setCurrentFencingToken(FENCING_TOKEN + 5);

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match ref lock fencing token");
    }

    @Test
    @DisplayName("proposeRequest maps recognized uq_active_break_glass_op violation to RemediationConcurrencyConflictException")
    void testProposeRequest_DataIntegrityViolation_UniqueConstraint() {
        when(requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL))
                .thenReturn(Optional.empty());

        ConstraintViolationException cve = new ConstraintViolationException("Unique constraint violation",
                new SQLException("duplicate key", "23505"), "uq_active_break_glass_op");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Conflict on uq_active_break_glass_op", cve);

        when(requestRepository.saveAndFlush(any(RemediationBreakGlassRequestEntity.class))).thenThrow(dive);

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Concurrent attempt", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("active pending break-glass request already exists");
    }

    @Test
    @DisplayName("proposeRequest propagates unrecognized DataIntegrityViolationException directly without mapping")
    void testProposeRequest_DataIntegrityViolation_UnrelatedConstraint() {
        when(requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL))
                .thenReturn(Optional.empty());

        ConstraintViolationException cve = new ConstraintViolationException("Foreign key violation",
                new SQLException("fk violation", "23503"), "fk_operation_id");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("FK failure", cve);

        when(requestRepository.saveAndFlush(any(RemediationBreakGlassRequestEntity.class))).thenThrow(dive);

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "FK attempt", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(RemediationConcurrencyConflictException.class);
    }

    @Test
    @DisplayName("proposeRequest throws RemediationOperationNotFoundException when operation is missing")
    void testProposeRequest_OperationNotFound() {
        UUID missingId = UUID.randomUUID();
        when(operationRepository.findById(missingId)).thenReturn(Optional.empty());

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(missingId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationOperationNotFoundException.class);
    }

    @Test
    @DisplayName("proposeRequest throws IllegalStateException when operation is not in RECOVERY_REQUIRED")
    void testProposeRequest_OperationNotInRecoveryRequired() {
        operation.setStatus(RemediationOperationStatus.CREATED);

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected RECOVERY_REQUIRED");
    }

    @Test
    @DisplayName("proposeRequest throws IllegalStateException when target ref is not frozen")
    void testProposeRequest_TargetRefNotFrozen() {
        refLock.setIsFrozen(false);

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not frozen");
    }

    @Test
    @DisplayName("proposeRequest throws IllegalStateException when physical Git ref SHA cannot be resolved")
    void testProposeRequest_GitRefUnresolvable() {
        when(gitCasAdapter.resolveRefSha(REPO_PATH, TARGET_REF)).thenReturn(Optional.empty());

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to resolve physical Git ref");
    }

    // =========================================================================
    // 2. Finalize KEEP_FROZEN Tests
    // =========================================================================

    @Test
    @DisplayName("finalizeKeepFrozen successfully executes KEEP_FROZEN with pessimistic row locks")
    void testFinalizeKeepFrozen_Success() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any(RemediationBreakGlassRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Retaining freeze after review");

        BreakGlassFinalizationOutcome outcome = service.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome).isNotNull();
        assertThat(outcome.isExecuted()).isTrue();
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.status()).isEqualTo(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);

        // Verify request state
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);
        assertThat(req.getApproverIssuer()).isEqualTo(ADMIN_BOB.issuer());
        assertThat(req.getApproverSubject()).isEqualTo(ADMIN_BOB.subject());
        assertThat(req.getResolvedAt()).isNotNull();
        assertThat(req.getExecutionNotes()).isEqualTo("Retaining freeze after review");

        // Verify target ref was locked via findByRefNameForUpdate
        verify(targetRefLockRepository).findByRefNameForUpdate(TARGET_REF);

        // Verify operation remains in RECOVERY_REQUIRED
        assertThat(operation.getStatus()).isEqualTo(RemediationOperationStatus.RECOVERY_REQUIRED);
        assertThat(operation.getRecoveryStatus()).isEqualTo("BREAK_GLASS_FREEZE_RETAINED");

        // Verify target ref lock remains frozen
        assertThat(refLock.getIsFrozen()).isTrue();

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_FREEZE_RETAINED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("finalizeKeepFrozen enforces inclusive expiry boundary: now == expiresAt must expire")
    void testFinalizeKeepFrozen_ExactExpiryBoundary_AtExpiry() {
        UUID requestId = UUID.randomUUID();
        Instant fixedExpiry = Instant.parse("2026-11-20T12:00:00Z");

        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        req.setExpiresAt(fixedExpiry);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // Inject clock fixed at EXACT expiry boundary: now == expiresAt
        Clock fixedClockAtExpiry = Clock.fixed(fixedExpiry, ZoneOffset.UTC);
        RemediationBreakGlassService serviceWithClock = new RemediationBreakGlassService(
                requestRepository,
                operationRepository,
                targetRefLockRepository,
                journalService,
                gitCasAdapter,
                repositoryLockManager,
                processLockManager,
                transactionTemplate,
                fixedClockAtExpiry
        );

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        BreakGlassFinalizationOutcome outcome = serviceWithClock.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.isExpired()).isTrue();
        assertThat(outcome.status()).isEqualTo(RemediationBreakGlassStatus.EXPIRED);
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.EXPIRED);
        assertThat(req.getExecutionNotes()).isNull();
        assertThat(req.getResolvedAt()).isEqualTo(fixedExpiry);

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_EXPIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("finalizeKeepFrozen allows approval when now is 1ms before expiresAt")
    void testFinalizeKeepFrozen_ExactExpiryBoundary_OneMillisBeforeExpiry() {
        UUID requestId = UUID.randomUUID();
        Instant fixedExpiry = Instant.parse("2026-11-20T12:00:00Z");

        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        req.setExpiresAt(fixedExpiry);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // Inject clock fixed at 1ms before expiry: now < expiresAt
        Clock fixedClockBeforeExpiry = Clock.fixed(fixedExpiry.minusMillis(1), ZoneOffset.UTC);
        RemediationBreakGlassService serviceWithClock = new RemediationBreakGlassService(
                requestRepository,
                operationRepository,
                targetRefLockRepository,
                journalService,
                gitCasAdapter,
                repositoryLockManager,
                processLockManager,
                transactionTemplate,
                fixedClockBeforeExpiry
        );

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Approved 1ms before expiry");

        BreakGlassFinalizationOutcome outcome = serviceWithClock.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.isExecuted()).isTrue();
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);
    }

    @Test
    @DisplayName("finalizeKeepFrozen enforces anti-self-approval and throws RemediationAuthorizationException")
    void testFinalizeKeepFrozen_AntiSelfApprovalRejected() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Self approval attempt");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_ALICE))
                .isInstanceOf(RemediationAuthorizationException.class)
                .hasMessageContaining("Anti-self-approval violation");

        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
        verify(journalService, never()).appendEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("finalizeKeepFrozen rejects scoping mismatch with IllegalArgumentException")
    void testFinalizeKeepFrozen_ScopingMismatch() {
        UUID requestId = UUID.randomUUID();
        UUID otherOpId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(otherOpId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to operation");
    }

    @Test
    @DisplayName("finalizeKeepFrozen throws NoSuchElementException when request not found")
    void testFinalizeKeepFrozen_RequestNotFound() {
        UUID requestId = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Break-glass request not found");
    }

    @Test
    @DisplayName("finalizeKeepFrozen rejects already terminal request with RemediationConcurrencyConflictException")
    void testFinalizeKeepFrozen_AlreadyTerminal() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        req.setStatus(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state");
    }

    @Test
    @DisplayName("finalizeKeepFrozen handles stale invalidation when physical Git SHA has shifted")
    void testFinalizeKeepFrozen_StaleGitShaShifted() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        String shiftedSha = "3333333333333333333333333333333333333333";
        when(gitCasAdapter.resolveRefSha(REPO_PATH, TARGET_REF)).thenReturn(Optional.of(shiftedSha));

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        BreakGlassFinalizationOutcome outcome = service.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.isStaleInvalidated()).isTrue();
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.STALE_INVALIDATED);
        assertThat(req.getExecutionNotes()).contains("Physical Git ref shifted");

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_INVALIDATED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(shiftedSha),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("finalizeKeepFrozen handles stale invalidation when fencing token moved")
    void testFinalizeKeepFrozen_StaleFencingTokenMoved() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        operation.setFencingToken(FENCING_TOKEN + 1);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        BreakGlassFinalizationOutcome outcome = service.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.isStaleInvalidated()).isTrue();
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.STALE_INVALIDATED);
        assertThat(req.getExecutionNotes()).contains("fencing token moved");
    }

    @Test
    @DisplayName("finalizeKeepFrozen handles stale invalidation when target ref is no longer frozen")
    void testFinalizeKeepFrozen_StaleRefUnfrozen() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        refLock.setIsFrozen(false);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        BreakGlassFinalizationOutcome outcome = service.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.isStaleInvalidated()).isTrue();
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.STALE_INVALIDATED);
        assertThat(req.getExecutionNotes()).contains("Target ref is no longer frozen");
    }

    @Test
    @DisplayName("finalizeKeepFrozen handles stale invalidation when operation left RECOVERY_REQUIRED")
    void testFinalizeKeepFrozen_StaleOperationLeftRecoveryRequired() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        operation.setStatus(RemediationOperationStatus.PERSISTED);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        BreakGlassFinalizationOutcome outcome = service.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.isStaleInvalidated()).isTrue();
        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.STALE_INVALIDATED);
        assertThat(req.getExecutionNotes()).contains("no longer in RECOVERY_REQUIRED status");
    }

    // =========================================================================
    // 3. Rejection Tests
    // =========================================================================

    @Test
    @DisplayName("rejectRequest updates status to REJECTED and appends BREAK_GLASS_REJECTED under lock hierarchy")
    void testRejectRequest_Success() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Recovery plan inadequate");

        RemediationBreakGlassRequestEntity result = service.rejectRequest(command, ADMIN_BOB);

        assertThat(result.getStatus()).isEqualTo(RemediationBreakGlassStatus.REJECTED);
        assertThat(result.getApproverIssuer()).isEqualTo(ADMIN_BOB.issuer());
        assertThat(result.getApproverSubject()).isEqualTo(ADMIN_BOB.subject());
        assertThat(result.getRejectionReason()).isEqualTo("Recovery plan inadequate");
        assertThat(result.getExecutionNotes()).isNull();
        assertThat(result.getResolvedAt()).isNotNull();

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_REJECTED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("rejectRequest enforces anti-self-approval")
    void testRejectRequest_AntiSelfApprovalRejected() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Self rejection attempt");

        assertThatThrownBy(() -> service.rejectRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationAuthorizationException.class)
                .hasMessageContaining("Anti-self-approval violation");

        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
    }

    @Test
    @DisplayName("rejectRequest throws RemediationConcurrencyConflictException when already terminal")
    void testRejectRequest_AlreadyTerminal() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        req.setStatus(RemediationBreakGlassStatus.REJECTED);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.rejectRequest(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state");
    }

    // =========================================================================
    // 4. Cancellation Tests
    // =========================================================================

    @Test
    @DisplayName("cancelRequest allows initiator to cancel pending request under lock hierarchy")
    void testCancelRequest_Success() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Proposed in error");

        RemediationBreakGlassRequestEntity result = service.cancelRequest(command, ADMIN_ALICE);

        assertThat(result.getStatus()).isEqualTo(RemediationBreakGlassStatus.CANCELLED);
        assertThat(result.getCancelledByIssuer()).isEqualTo(ADMIN_ALICE.issuer());
        assertThat(result.getCancelledBySubject()).isEqualTo(ADMIN_ALICE.subject());
        assertThat(result.getCancellationReason()).isEqualTo("Proposed in error");
        assertThat(result.getCancelledAt()).isNotNull();

        verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_CANCELLED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(RemediationOperationStatus.RECOVERY_REQUIRED),
                eq(TARGET_REF),
                eq(OLD_SHA),
                eq(CURRENT_SHA),
                isNull(),
                anyString()
        );
    }

    @Test
    @DisplayName("cancelRequest rejects non-initiator with RemediationAuthorizationException")
    void testCancelRequest_NonInitiatorRejected() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Unauthorized cancel");

        assertThatThrownBy(() -> service.cancelRequest(command, ADMIN_BOB))
                .isInstanceOf(RemediationAuthorizationException.class)
                .hasMessageContaining("Only the proposing administrator");

        assertThat(req.getStatus()).isEqualTo(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
    }

    @Test
    @DisplayName("cancelRequest throws RemediationConcurrencyConflictException when already terminal")
    void testCancelRequest_AlreadyTerminal() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        req.setStatus(RemediationBreakGlassStatus.CANCELLED);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.cancelRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state");
    }

    // =========================================================================
    // 5. Read / Query Operations Tests
    // =========================================================================

    @Test
    @DisplayName("getRequest filters by operation scoping")
    void testGetRequest_Scoping() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        assertThat(service.getRequest(operationId, requestId)).isPresent();
        assertThat(service.getRequest(UUID.randomUUID(), requestId)).isEmpty();
    }

    @Test
    @DisplayName("getActiveRequest delegates to repository findByOperationIdAndStatus")
    void testGetActiveRequest() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findByOperationIdAndStatus(operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL))
                .thenReturn(Optional.of(req));

        Optional<RemediationBreakGlassRequestEntity> result = service.getActiveRequest(operationId);
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("getRequestsForOperation delegates to repository findByOperationIdOrderByCreatedAtDesc")
    void testGetRequestsForOperation() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findByOperationIdOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(req));

        List<RemediationBreakGlassRequestEntity> result = service.getRequestsForOperation(operationId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(requestId);
    }

    // =========================================================================
    // 6. Lock Hierarchy and Resource Release Tests
    // =========================================================================

    @Test
    @DisplayName("finalizeKeepFrozen releases OS repository lock and in-process lock even on exception")
    void testFinalizeKeepFrozen_LockReleaseOnException() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        AutoCloseableLock mockRepoLock = mock(AutoCloseableLock.class);
        when(repositoryLockManager.acquireLock(eq(REPO_PATH), any(Duration.class)))
                .thenReturn(mockRepoLock);

        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId))
                .thenThrow(new RuntimeException("Simulated database failure"));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated database failure");

        // Verify OS repo lock was reliably closed
        verify(mockRepoLock).close();

        // Verify in-process lock was released (can be re-acquired immediately)
        var refLock = processLockManager.getLock(REPO_PATH, TARGET_REF);
        assertThat(refLock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("proposeRequest releases OS repository lock and in-process lock even on exception")
    void testProposeRequest_LockReleaseOnException() {
        AutoCloseableLock mockRepoLock = mock(AutoCloseableLock.class);
        when(repositoryLockManager.acquireLock(eq(REPO_PATH), any(Duration.class)))
                .thenReturn(mockRepoLock);

        // Fail during locked operation lookup
        when(operationRepository.findByIdForUpdate(operationId))
                .thenThrow(new RuntimeException("Simulated DB error in proposeRequest"));

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(operationId, "Rationale", null, null, null);

        assertThatThrownBy(() -> service.proposeRequest(command, ADMIN_ALICE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated DB error in proposeRequest");

        verify(mockRepoLock).close();
        var refLock = processLockManager.getLock(REPO_PATH, TARGET_REF);
        assertThat(refLock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("rejectRequest releases OS repository lock and in-process lock even on exception")
    void testRejectRequest_LockReleaseOnException() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        AutoCloseableLock mockRepoLock = mock(AutoCloseableLock.class);
        when(repositoryLockManager.acquireLock(eq(REPO_PATH), any(Duration.class)))
                .thenReturn(mockRepoLock);

        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId))
                .thenThrow(new RuntimeException("Simulated DB error in rejectRequest"));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.rejectRequest(command, ADMIN_BOB))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated DB error in rejectRequest");

        verify(mockRepoLock).close();
        var refLock = processLockManager.getLock(REPO_PATH, TARGET_REF);
        assertThat(refLock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("cancelRequest releases OS repository lock and in-process lock even on exception")
    void testCancelRequest_LockReleaseOnException() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        AutoCloseableLock mockRepoLock = mock(AutoCloseableLock.class);
        when(repositoryLockManager.acquireLock(eq(REPO_PATH), any(Duration.class)))
                .thenReturn(mockRepoLock);

        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId))
                .thenThrow(new RuntimeException("Simulated DB error in cancelRequest"));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.cancelRequest(command, ADMIN_ALICE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated DB error in cancelRequest");

        verify(mockRepoLock).close();
        var refLock = processLockManager.getLock(REPO_PATH, TARGET_REF);
        assertThat(refLock.isLocked()).isFalse();
    }

    // =========================================================================
    // 7. Concurrent Finalize vs Reject / Cancel Cross-Transitions
    // =========================================================================

    @Test
    @DisplayName("finalizeKeepFrozen fails with conflict if request was already rejected concurrently")
    void testConcurrentFinalizeVsReject() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        // Losing finalize sees row after reject committed
        req.setStatus(RemediationBreakGlassStatus.REJECTED);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state: REJECTED");
    }

    @Test
    @DisplayName("finalizeKeepFrozen fails with conflict if request was already cancelled concurrently")
    void testConcurrentFinalizeVsCancel() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        // Losing finalize sees row after cancel committed
        req.setStatus(RemediationBreakGlassStatus.CANCELLED);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state: CANCELLED");
    }

    @Test
    @DisplayName("rejectRequest fails with conflict if request was already finalized concurrently")
    void testConcurrentRejectVsFinalize() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        // Losing reject sees row after finalize committed
        req.setStatus(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.rejectRequest(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state: EXECUTED_KEPT_FROZEN");
    }

    @Test
    @DisplayName("cancelRequest fails with conflict if request was already finalized concurrently")
    void testConcurrentCancelVsFinalize() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        // Losing cancel sees row after finalize committed
        req.setStatus(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.cancelRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("already in terminal state: EXECUTED_KEPT_FROZEN");
    }

    // =========================================================================
    // 8. Stage 4.3.3.4: Lock-Order Normalization & In-Order Verification Tests
    // =========================================================================

    @Test
    @DisplayName("finalizeKeepFrozen acquires DB locks in strict monotonic order: R3 Op -> R4 TargetRef -> R5 Request -> R6 Journal")
    void testFinalizeKeepFrozen_StrictLockOrder() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Verified safety");
        BreakGlassFinalizationOutcome outcome = service.finalizeKeepFrozen(command, ADMIN_BOB);

        assertThat(outcome.success()).isTrue();

        InOrder order = inOrder(operationRepository, targetRefLockRepository, requestRepository, journalService);
        order.verify(operationRepository).findByIdForUpdate(operationId);
        order.verify(targetRefLockRepository).findByRefNameForUpdate(TARGET_REF);
        order.verify(requestRepository).findByIdAndOperationIdForUpdate(requestId, operationId);
        order.verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_FREEZE_RETAINED),
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("rejectRequest acquires DB locks in strict monotonic order: R3 Op -> R5 Request -> R6 Journal")
    void testRejectRequest_StrictLockOrder() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Audit failed");
        RemediationBreakGlassRequestEntity result = service.rejectRequest(command, ADMIN_BOB);

        assertThat(result.getStatus()).isEqualTo(RemediationBreakGlassStatus.REJECTED);

        InOrder order = inOrder(operationRepository, requestRepository, journalService);
        order.verify(operationRepository).findByIdForUpdate(operationId);
        order.verify(requestRepository).findByIdAndOperationIdForUpdate(requestId, operationId);
        order.verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_REJECTED),
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("cancelRequest acquires DB locks in strict monotonic order: R3 Op -> R5 Request -> R6 Journal")
    void testCancelRequest_StrictLockOrder() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(req));
        when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Self-cancelled");
        RemediationBreakGlassRequestEntity result = service.cancelRequest(command, ADMIN_ALICE);

        assertThat(result.getStatus()).isEqualTo(RemediationBreakGlassStatus.CANCELLED);

        InOrder order = inOrder(operationRepository, requestRepository, journalService);
        order.verify(operationRepository).findByIdForUpdate(operationId);
        order.verify(requestRepository).findByIdAndOperationIdForUpdate(requestId, operationId);
        order.verify(journalService).appendEvent(
                eq(operationId),
                eq(FENCING_TOKEN),
                eq(RemediationOperationEventType.BREAK_GLASS_CANCELLED),
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("finalizeKeepFrozen rejects operation repo/ref drift between preliminary read and lock acquisition")
    void testFinalizeKeepFrozen_RejectsOperationDrift() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        RemediationOperationEntity driftedOp = new RemediationOperationEntity();
        driftedOp.setId(operationId);
        driftedOp.setRepositoryPath(REPO_PATH);
        driftedOp.setTargetRef("refs/heads/drifted-branch");
        driftedOp.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
        driftedOp.setFencingToken(FENCING_TOKEN);
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(driftedOp));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("Operation repository path or target ref changed during lock acquisition");
    }

    @Test
    @DisplayName("finalizeKeepFrozen rejects request repo/ref drift from locked operation")
    void testFinalizeKeepFrozen_RejectsRequestDrift() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        RemediationBreakGlassRequestEntity driftedReq = createPendingEntity(requestId, operationId);
        driftedReq.setTargetRef("refs/heads/drifted-branch");
        when(requestRepository.findByIdAndOperationIdForUpdate(requestId, operationId)).thenReturn(Optional.of(driftedReq));

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(operationId, requestId, "Notes");

        assertThatThrownBy(() -> service.finalizeKeepFrozen(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("Break-glass request repository path or target ref drifted from locked operation");
    }

    @Test
    @DisplayName("rejectRequest rejects operation repo/ref drift between preliminary read and lock acquisition")
    void testRejectRequest_RejectsOperationDrift() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        RemediationOperationEntity driftedOp = new RemediationOperationEntity();
        driftedOp.setId(operationId);
        driftedOp.setRepositoryPath("C:/repos/drifted-repo");
        driftedOp.setTargetRef(TARGET_REF);
        driftedOp.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(driftedOp));

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.rejectRequest(command, ADMIN_BOB))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("Operation repository path or target ref changed during lock acquisition");
    }

    @Test
    @DisplayName("cancelRequest rejects operation repo/ref drift between preliminary read and lock acquisition")
    void testCancelRequest_RejectsOperationDrift() {
        UUID requestId = UUID.randomUUID();
        RemediationBreakGlassRequestEntity req = createPendingEntity(requestId, operationId);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        RemediationOperationEntity driftedOp = new RemediationOperationEntity();
        driftedOp.setId(operationId);
        driftedOp.setRepositoryPath("C:/repos/drifted-repo");
        driftedOp.setTargetRef(TARGET_REF);
        driftedOp.setStatus(RemediationOperationStatus.RECOVERY_REQUIRED);
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(driftedOp));

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(operationId, requestId, "Reason");

        assertThatThrownBy(() -> service.cancelRequest(command, ADMIN_ALICE))
                .isInstanceOf(RemediationConcurrencyConflictException.class)
                .hasMessageContaining("Operation repository path or target ref changed during lock acquisition");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private RemediationBreakGlassRequestEntity createPendingEntity(UUID requestId, UUID operationId) {
        RemediationBreakGlassRequestEntity e = new RemediationBreakGlassRequestEntity();
        e.setId(requestId);
        e.setOperationId(operationId);
        e.setRepositoryPath(REPO_PATH);
        e.setTargetRef(TARGET_REF);
        e.setExpectedOldSha(OLD_SHA);
        e.setProposedNewSha(NEW_SHA);
        e.setSnapshotGitSha(CURRENT_SHA);
        e.setOperationFencingToken(FENCING_TOKEN);
        e.setRequestedDecision(RemediationBreakGlassDecision.KEEP_FROZEN);
        e.setStatus(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
        e.setRationale("Investigation showed ambiguous CAS conflict; holding freeze");
        e.setInitiatorIssuer(ADMIN_ALICE.issuer());
        e.setInitiatorSubject(ADMIN_ALICE.subject());
        e.setCreatedAt(Instant.now());
        e.setExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        return e;
    }
}
