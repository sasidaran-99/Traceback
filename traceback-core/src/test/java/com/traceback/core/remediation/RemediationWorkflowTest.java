package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.domain.RemediationOperationEventEntity;
import com.traceback.core.domain.RemediationTargetRefLockEntity;
import com.traceback.core.remediation.git.JGitCasAdapter;
import com.traceback.core.repository.RemediationOperationEventRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.core.repository.RemediationTargetRefLockRepository;
import com.traceback.core.security.SecretRedactor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemediationWorkflowTest {

    @TempDir
    Path tempDir;

    private Git git;
    private ObjectId commitA;
    private ObjectId commitB;

    private RemediationOperationRepository operationRepository;
    private RemediationOperationEventRepository eventRepository;
    private RemediationTargetRefLockRepository targetRefLockRepository;

    private RemediationLockCoordinator lockCoordinator;
    private RemediationJournalService journalService;
    private JGitCasAdapter gitCasAdapter;
    private SecretRedactor secretRedactor;

    private RemediationExecutionCoordinator executionCoordinator;
    private RemediationRecoveryCoordinator recoveryCoordinator;

    // In-memory backing stores for mock simulation
    private Map<UUID, RemediationOperationEntity> operations;
    private List<RemediationOperationEventEntity> events;
    private Map<String, RemediationTargetRefLockEntity> locks;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();

        File f1 = new File(tempDir.toFile(), "app.txt");
        Files.writeString(f1.toPath(), "version 1");
        git.add().addFilepattern("app.txt").call();
        commitA = git.commit().setMessage("Commit A").call().getId();

        Files.writeString(f1.toPath(), "version 2");
        git.add().addFilepattern("app.txt").call();
        commitB = git.commit().setMessage("Commit B").call().getId();

        operations = new HashMap<>();
        events = new ArrayList<>();
        locks = new HashMap<>();

        operationRepository = mock(RemediationOperationRepository.class);
        eventRepository = mock(RemediationOperationEventRepository.class);
        targetRefLockRepository = mock(RemediationTargetRefLockRepository.class);

        when(operationRepository.save(any(RemediationOperationEntity.class))).thenAnswer(inv -> {
            RemediationOperationEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            operations.put(e.getId(), e);
            return e;
        });

        when(operationRepository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(operations.get(inv.getArgument(0))));
        when(operationRepository.findByIdForUpdate(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(operations.get(inv.getArgument(0))));
        when(operationRepository.findByIdempotencyKey(any(String.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return operations.values().stream().filter(o -> key.equals(o.getIdempotencyKey())).findFirst();
        });

        when(operationRepository.findExpiredLeaseOperations(any(), any())).thenAnswer(inv -> {
            java.util.Collection<RemediationOperationStatus> statuses = inv.getArgument(0);
            java.time.Instant now = inv.getArgument(1);
            return operations.values().stream()
                    .filter(o -> statuses.contains(o.getStatus()) && o.getLeaseExpiresAt() != null && o.getLeaseExpiresAt().isBefore(now))
                    .toList();
        });

        when(operationRepository.findStaleProposals(any(), any())).thenAnswer(inv -> {
            RemediationOperationStatus status = inv.getArgument(0);
            java.time.Instant cutoff = inv.getArgument(1);
            return operations.values().stream()
                    .filter(o -> o.getStatus() == status && o.getCreatedAt() != null && o.getCreatedAt().isBefore(cutoff))
                    .toList();
        });

        when(eventRepository.save(any(RemediationOperationEventEntity.class))).thenAnswer(inv -> {
            RemediationOperationEventEntity ev = inv.getArgument(0);
            if (ev.getId() == null) {
                ev.setId(UUID.randomUUID());
            }
            events.add(ev);
            return ev;
        });

        when(eventRepository.existsByOperationIdAndEventType(any(UUID.class), any(RemediationOperationEventType.class))).thenAnswer(inv -> {
            UUID opId = inv.getArgument(0);
            RemediationOperationEventType type = inv.getArgument(1);
            return events.stream().anyMatch(e -> opId.equals(e.getOperationId()) && type == e.getEventType());
        });

        when(eventRepository.findByOperationIdOrderByCreatedAtAsc(any(UUID.class))).thenAnswer(inv -> {
            UUID opId = inv.getArgument(0);
            return events.stream().filter(e -> opId.equals(e.getOperationId())).toList();
        });

        when(targetRefLockRepository.findByRefNameForShare(any(String.class))).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            RemediationTargetRefLockEntity l = locks.computeIfAbsent(ref, RemediationTargetRefLockEntity::new);
            return Optional.of(l);
        });

        when(targetRefLockRepository.findByRefNameForUpdate(any(String.class))).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            RemediationTargetRefLockEntity l = locks.computeIfAbsent(ref, RemediationTargetRefLockEntity::new);
            return Optional.of(l);
        });

        when(targetRefLockRepository.save(any(RemediationTargetRefLockEntity.class))).thenAnswer(inv -> {
            RemediationTargetRefLockEntity l = inv.getArgument(0);
            locks.put(l.getRefName(), l);
            return l;
        });

        secretRedactor = new SecretRedactor();
        lockCoordinator = new RemediationLockCoordinator(targetRefLockRepository);
        journalService = new RemediationJournalService(eventRepository, secretRedactor);
        gitCasAdapter = new JGitCasAdapter(List.of(tempDir.toString()));

        RemediationProcessLockManager processLockManager = new RemediationProcessLockManager();

        executionCoordinator = new RemediationExecutionCoordinator(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                secretRedactor,
                processLockManager
        );

        recoveryCoordinator = new RemediationRecoveryCoordinator(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                processLockManager
        );
    }

    @Test
    @DisplayName("End-to-end remediation operation execution succeeds and advances ref")
    void testSuccessfulExecution() {
        String targetRef = "refs/heads/feature-patch";

        // Initialize target ref pointing to commitA
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "idem-test-1",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        assertNotNull(op.getId());
        assertEquals(RemediationOperationStatus.CREATED, op.getStatus());

        RemediationOperationEntity executed = executionCoordinator.executeOperation(op.getId(), "worker-node-1");

        assertEquals(RemediationOperationStatus.PERSISTED, executed.getStatus());

        // Verify Git ref has been advanced to commitB
        Optional<String> refSha = gitCasAdapter.resolveRefSha(tempDir.toString(), targetRef);
        assertTrue(refSha.isPresent());
        assertEquals(commitB.name(), refSha.get());

        // Verify journal events
        List<RemediationOperationEventEntity> opEvents = journalService.getEventsForOperation(op.getId());
        assertTrue(opEvents.stream().anyMatch(e -> e.getEventType() == RemediationOperationEventType.CREATED));
        assertTrue(opEvents.stream().anyMatch(e -> e.getEventType() == RemediationOperationEventType.PRE_GIT_CAS_INTENT));
        assertTrue(opEvents.stream().anyMatch(e -> e.getEventType() == RemediationOperationEventType.GIT_CAS_SUCCEEDED));
        assertTrue(opEvents.stream().anyMatch(e -> e.getEventType() == RemediationOperationEventType.PERSISTED));
    }

    @Test
    @DisplayName("Idempotent proposal submission returns existing operation")
    void testIdempotentProposal() {
        String targetRef = "refs/heads/feature-idem";

        RemediationOperationEntity op1 = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "idem-key-duplicate",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        RemediationOperationEntity op2 = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "idem-key-duplicate",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        assertEquals(op1.getId(), op2.getId());
    }

    @Test
    @DisplayName("Conflicting idempotency key throws RemediationConcurrencyConflictException")
    void testConflictingIdempotencyKey() {
        String targetRef = "refs/heads/feature-conflict";

        executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "idem-conflict",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        assertThrows(RemediationConcurrencyConflictException.class, () ->
                executionCoordinator.proposeOperation(
                        UUID.randomUUID(),
                        "idem-conflict",
                        tempDir.toString(),
                        targetRef,
                        commitA.name(),
                        "0000000000000000000000000000000000000000",
                        RemediationOperationType.BRANCH_PATCH_PROMOTION,
                        "admin-tester"
                )
        );
    }

    @Test
    @DisplayName("Frozen target ref rejects operations with RemediationTargetRefFrozenException")
    void testFrozenTargetRefRejection() {
        String targetRef = "refs/heads/frozen-branch";
        lockCoordinator.freezeTargetRef(targetRef, "Administrative security lock");

        assertThrows(RemediationTargetRefFrozenException.class, () ->
                executionCoordinator.proposeOperation(
                        UUID.randomUUID(),
                        "frozen-key",
                        tempDir.toString(),
                        targetRef,
                        commitA.name(),
                        commitB.name(),
                        RemediationOperationType.BRANCH_PATCH_PROMOTION,
                        "admin-tester"
                )
        );
    }

    @Test
    @DisplayName("Stale fencing token rejects worker Phase A intent")
    void testStaleFencingToken() {
        String targetRef = "refs/heads/fencing-branch";
        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "fence-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        // Worker attempts with wrong fencing token (999 instead of 1)
        assertThrows(StaleFencingTokenException.class, () ->
                executionCoordinator.recordDurableIntent(op.getId(), "slow-worker", 999L)
        );
    }

    @Test
    @DisplayName("Crash after intent: recovery safely aborts when Git ref is unmutated")
    void testCrashAfterIntentRecovery() {
        String targetRef = "refs/heads/crash-pre-cas";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "crash-1-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "crashing-worker", 1L);

        // Simulate crash: Worker dies before Phase B
        RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(op.getId());

        assertEquals(RemediationOperationStatus.FAILED, recovered.getStatus());
        assertEquals("SAFELY_ABORTED_PRE_CAS", recovered.getRecoveryStatus());
        assertFalse(locks.get(targetRef).getIsFrozen());
    }

    @Test
    @DisplayName("Crash after Git CAS: ambiguous attribution fails closed, freezes ref, and escalates")
    void testCrashAfterGitCasAmbiguousAttribution() {
        String targetRef = "refs/heads/crash-ambiguous";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "crash-2-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "crashing-worker", 1L);

        // Git CAS executed (ref moved to commitB), but worker crashes before committing Phase C
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, commitA.name(), commitB.name(), false);

        // Recovery runs without durable post-CAS journal entry
        RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(op.getId());

        assertEquals(RemediationOperationStatus.RECOVERY_REQUIRED, recovered.getStatus());
        assertEquals("AMBIGUOUS_EXECUTION_ESCALATED", recovered.getRecoveryStatus());

        // Target ref MUST be frozen to protect system integrity
        assertTrue(locks.get(targetRef).getIsFrozen());
    }

    @Test
    @DisplayName("Recovery finalizes operation when post-CAS success was committed before crash")
    void testRecoveryFromCommittedPostSuccess() {
        String targetRef = "refs/heads/crash-post-committed";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "crash-3-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "worker-committed", 1L);

        // Simulate post-CAS success committed
        journalService.appendEvent(
                op.getId(),
                1L,
                RemediationOperationEventType.GIT_CAS_SUCCEEDED,
                RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                RemediationOperationStatus.GIT_CAS_SUCCEEDED,
                targetRef,
                commitA.name(),
                commitB.name(),
                "FAST_FORWARD",
                "CAS succeeded"
        );

        RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(op.getId());

        assertEquals(RemediationOperationStatus.PERSISTED, recovered.getStatus());
        assertEquals("RECOVERED_FROM_COMMITTED_SUCCESS", recovered.getRecoveryStatus());
        assertFalse(locks.get(targetRef).getIsFrozen());
    }

    @Test
    @DisplayName("Secrets in journal details are automatically redacted")
    void testSecretRedaction() {
        String secretDetail = "Deploying with token ghp_123456789012345678901234567890123456 to cluster";
        RemediationOperationEventEntity event = journalService.appendEvent(
                UUID.randomUUID(),
                1L,
                RemediationOperationEventType.CREATED,
                null,
                RemediationOperationStatus.CREATED,
                "refs/heads/main",
                commitA.name(),
                commitB.name(),
                null,
                secretDetail
        );

        assertNotNull(event.getDetails());
        assertFalse(event.getDetails().contains("ghp_123456789012345678901234567890123456"));
        assertTrue(event.getDetails().contains("[REDACTED]"));
    }

    @Test
    @DisplayName("Paused worker cannot commit Git CAS after lease has expired")
    void testPausedWorkerCannotCommitAfterLeaseExpires() {
        String targetRef = "refs/heads/lease-expire-branch";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "lease-expire-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "slow-worker", 1L);

        // Simulate lease expiration
        op.setLeaseExpiresAt(java.time.Instant.now().minusSeconds(10));

        // When worker wakes up and attempts Phase B Git CAS
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                executionCoordinator.executeGitCas(op.getId(), 1L)
        );
        assertTrue(ex.getMessage().contains("Worker lease expired"));

        // Git ref must NOT have been mutated
        Optional<String> refSha = gitCasAdapter.resolveRefSha(tempDir.toString(), targetRef);
        assertTrue(refSha.isPresent());
        assertEquals(commitA.name(), refSha.get());
    }

    @Test
    @DisplayName("Paused worker cannot commit Git CAS after recovery has intervened")
    void testPausedWorkerCannotCommitAfterRecoveryIntervened() {
        String targetRef = "refs/heads/recovery-intervene-branch";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "recovery-intervene-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "slow-worker", 1L);

        // Recovery intervenes while worker is paused
        RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(op.getId());
        assertEquals(RemediationOperationStatus.FAILED, recovered.getStatus());
        assertEquals(2L, recovered.getFencingToken());

        // Worker wakes up with stale fencing token 1L
        assertThrows(StaleFencingTokenException.class, () ->
                executionCoordinator.executeGitCas(op.getId(), 1L)
        );

        // Git ref must NOT have been mutated
        Optional<String> refSha = gitCasAdapter.resolveRefSha(tempDir.toString(), targetRef);
        assertTrue(refSha.isPresent());
        assertEquals(commitA.name(), refSha.get());
    }

    @Test
    @DisplayName("Recovery is rejected when an active worker thread is holding the process lock")
    void testRecoveryRejectedWhileWorkerHoldsProcessLock() {
        String targetRef = "refs/heads/process-lock-active";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "process-lock-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "active-worker", 1L);

        // Simulate active worker holding process lock
        java.util.concurrent.locks.ReentrantLock lock = executionCoordinator.getProcessLockManager()
                .getLock(tempDir.toString(), targetRef);
        lock.lock();
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    recoveryCoordinator.auditAndRecoverOperation(op.getId())
            );
            assertTrue(ex.getMessage().contains("worker is actively executing"));
        } finally {
            lock.unlock();
        }
    }

    @Test
    @DisplayName("Stale CREATED proposals are automatically cancelled by recovery scanner")
    void testStaleProposalCancellation() {
        String targetRef = "refs/heads/stale-created-branch";

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "stale-created-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        assertEquals(RemediationOperationStatus.CREATED, op.getStatus());

        // Backdate created timestamp past the 1-hour timeout
        op.setCreatedAt(java.time.Instant.now().minusSeconds(7200));

        int recoveredCount = recoveryCoordinator.recoverExpiredOperations();
        assertTrue(recoveredCount >= 1);

        RemediationOperationEntity cancelled = operations.get(op.getId());
        assertEquals(RemediationOperationStatus.CANCELLED, cancelled.getStatus());
        assertEquals("STALE_PROPOSAL_CANCELLED", cancelled.getRecoveryStatus());

        List<RemediationOperationEventEntity> opEvents = journalService.getEventsForOperation(op.getId());
        assertTrue(opEvents.stream().anyMatch(e -> e.getEventType() == RemediationOperationEventType.OPERATION_CANCELLED));
    }

    @Test
    @DisplayName("Recovery is idempotent across multiple invocations")
    void testRecoveryIdempotency() {
        String targetRef = "refs/heads/idempotent-recovery";
        gitCasAdapter.executeCompareAndSwap(tempDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "idempotent-rec-key",
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "crashing-worker", 1L);

        RemediationOperationEntity firstRecovery = recoveryCoordinator.auditAndRecoverOperation(op.getId());
        assertEquals(RemediationOperationStatus.FAILED, firstRecovery.getStatus());

        RemediationOperationEntity secondRecovery = recoveryCoordinator.auditAndRecoverOperation(op.getId());
        assertEquals(RemediationOperationStatus.FAILED, secondRecovery.getStatus());
        assertEquals(firstRecovery.getFencingToken(), secondRecovery.getFencingToken());
    }

    @Test
    @DisplayName("Spring selects 5-arg constructor via @Autowired and initializes default process lock manager")
    void testSpringConstructorInjectionSelection() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(RemediationOperationRepository.class, () -> operationRepository);
            context.registerBean(RemediationLockCoordinator.class, () -> lockCoordinator);
            context.registerBean(RemediationJournalService.class, () -> journalService);
            context.registerBean(com.traceback.core.remediation.git.GitCasAdapter.class, () -> gitCasAdapter);
            context.registerBean(SecretRedactor.class, () -> secretRedactor);
            context.registerBean(RemediationExecutionCoordinator.class);
            context.refresh();

            RemediationExecutionCoordinator bean = context.getBean(RemediationExecutionCoordinator.class);
            assertNotNull(bean, "RemediationExecutionCoordinator bean should be successfully created by Spring");
            assertNotNull(bean.getProcessLockManager(), "ProcessLockManager should be instantiated via default delegation");
        }
    }

    @Test
    @DisplayName("6-arg constructor preserves custom process lock manager injection")
    void testSixArgumentConstructorPreservesCustomProcessLockManager() {
        RemediationProcessLockManager customLockManager = new RemediationProcessLockManager();
        RemediationExecutionCoordinator coord = new RemediationExecutionCoordinator(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                secretRedactor,
                customLockManager
        );
        assertNotNull(coord);
        org.junit.jupiter.api.Assertions.assertSame(customLockManager, coord.getProcessLockManager());
    }

    @Test
    @DisplayName("Spring selects 4-arg constructor via @Autowired for RemediationRecoveryCoordinator")
    void testSpringRecoveryCoordinatorConstructorInjectionSelection() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(RemediationOperationRepository.class, () -> operationRepository);
            context.registerBean(RemediationLockCoordinator.class, () -> lockCoordinator);
            context.registerBean(RemediationJournalService.class, () -> journalService);
            context.registerBean(com.traceback.core.remediation.git.GitCasAdapter.class, () -> gitCasAdapter);
            context.registerBean(RemediationRecoveryCoordinator.class);
            context.refresh();

            RemediationRecoveryCoordinator bean = context.getBean(RemediationRecoveryCoordinator.class);
            assertNotNull(bean, "RemediationRecoveryCoordinator bean should be successfully created by Spring");

            Object processLockManager = org.springframework.test.util.ReflectionTestUtils.getField(bean, "processLockManager");
            assertNotNull(processLockManager, "Default process lock manager should be initialized via 4-arg constructor delegation");
        }
    }

    @Test
    @DisplayName("5-arg constructor of RemediationRecoveryCoordinator preserves custom process lock manager injection")
    void testFiveArgumentRecoveryCoordinatorPreservesCustomProcessLockManager() {
        RemediationProcessLockManager customLockManager = new RemediationProcessLockManager();
        RemediationRecoveryCoordinator coord = new RemediationRecoveryCoordinator(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                customLockManager
        );
        assertNotNull(coord);
        Object injectedLockManager = org.springframework.test.util.ReflectionTestUtils.getField(coord, "processLockManager");
        org.junit.jupiter.api.Assertions.assertSame(customLockManager, injectedLockManager);
    }
}
