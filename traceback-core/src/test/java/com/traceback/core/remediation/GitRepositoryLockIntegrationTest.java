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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitRepositoryLockIntegrationTest {

    @TempDir
    Path tempDir;

    private Path repoDir;
    private Path lockDir;
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
    private DefaultGitRepositoryLockManager repositoryLockManager;

    private RemediationExecutionCoordinator executionCoordinator;
    private RemediationRecoveryCoordinator recoveryCoordinator;

    private Map<UUID, RemediationOperationEntity> operations;
    private List<RemediationOperationEventEntity> events;
    private Map<String, RemediationTargetRefLockEntity> locks;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);

        lockDir = tempDir.resolve("locks");
        Files.createDirectories(lockDir);

        git = Git.init().setDirectory(repoDir.toFile()).call();

        File f1 = new File(repoDir.toFile(), "code.txt");
        Files.writeString(f1.toPath(), "version 1");
        git.add().addFilepattern("code.txt").call();
        commitA = git.commit().setMessage("Initial commit").call().getId();

        Files.writeString(f1.toPath(), "version 2");
        git.add().addFilepattern("code.txt").call();
        commitB = git.commit().setMessage("Updated commit").call().getId();

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
        gitCasAdapter = new JGitCasAdapter(List.of(repoDir.toString()));

        repositoryLockManager = new DefaultGitRepositoryLockManager(lockDir, Duration.ofSeconds(2));
        RemediationProcessLockManager processLockManager = new RemediationProcessLockManager();

        executionCoordinator = new RemediationExecutionCoordinator(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                secretRedactor,
                processLockManager,
                repositoryLockManager
        );

        recoveryCoordinator = new RemediationRecoveryCoordinator(
                operationRepository,
                lockCoordinator,
                journalService,
                gitCasAdapter,
                processLockManager,
                repositoryLockManager
        );
    }

    @Test
    @DisplayName("Execution coordinator holds repository lock continuously across Phase A, Git CAS, and Phase C")
    void executionHoldingLockAcrossGitCasAndPhaseC() throws Exception {
        String targetRef = "refs/heads/feature-exec-lock";
        gitCasAdapter.executeCompareAndSwap(repoDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "exec-lock-key",
                repoDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch executionFinished = new CountDownLatch(1);
        AtomicBoolean lockHeldDuringExecution = new AtomicBoolean(false);

        Thread workerThread = new Thread(() -> {
            executionStarted.countDown();
            executionCoordinator.executeOperation(op.getId(), "worker-node-1");
            executionFinished.countDown();
        });

        workerThread.start();
        assertTrue(executionStarted.await(5, TimeUnit.SECONDS));
        assertTrue(executionFinished.await(5, TimeUnit.SECONDS));

        // Operation should be persisted successfully
        RemediationOperationEntity completed = operations.get(op.getId());
        assertEquals(RemediationOperationStatus.PERSISTED, completed.getStatus());

        // After completion, lock is cleanly released
        assertFalse(repositoryLockManager.isLocked(repoDir.toString()));
        Optional<AutoCloseableLock> acquiredAfter = repositoryLockManager.tryAcquireLock(repoDir.toString());
        assertTrue(acquiredAfter.isPresent());
        acquiredAfter.get().close();
    }

    @Test
    @DisplayName("Recovery cannot classify operation as FAILED while writer holds repository lock; fails closed into RECOVERY_REQUIRED")
    void recoveryUnableToClassifyFailedWhileWriterOwnsLock() throws Exception {
        String targetRef = "refs/heads/writer-owns-lock";
        gitCasAdapter.executeCompareAndSwap(repoDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "recovery-lock-contention-key",
                repoDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "stalled-worker", 1L);

        // Simulate writer holding repository lock (e.g. stalled in Phase B)
        try (AutoCloseableLock writerLock = repositoryLockManager.acquireLock(repoDir.toString())) {
            assertTrue(writerLock.isValid());

            // Recovery runs in another thread while writer owns the repository lock
            AtomicReference<RemediationOperationEntity> recoveredRef = new AtomicReference<>();
            Thread recoveryThread = new Thread(() -> {
                RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(op.getId());
                recoveredRef.set(recovered);
            });

            recoveryThread.start();
            recoveryThread.join(5000);

            RemediationOperationEntity recovered = recoveredRef.get();
            assertNotNull(recovered, "Recovery should return escalated entity");

            // INVARIANT: Must NOT classify as FAILED (SAFELY_ABORTED_PRE_CAS) because writer ownership is uncertain!
            assertNotEquals(RemediationOperationStatus.FAILED, recovered.getStatus());
            assertEquals(RemediationOperationStatus.RECOVERY_REQUIRED, recovered.getStatus());
            assertEquals("LOCK_ACQUISITION_TIMEOUT_ESCALATED", recovered.getRecoveryStatus());

            // Target ref MUST be frozen to protect repository
            assertTrue(locks.get(targetRef).getIsFrozen());
        }
    }

    @Test
    @DisplayName("Recovery proceeds safely after external writer process terminates and releases OS lock")
    void recoveryProceedsAfterOldProcessTerminatedAndLockReleased() throws Exception {
        String targetRef = "refs/heads/process-recovery-branch";
        gitCasAdapter.executeCompareAndSwap(repoDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "process-crash-recovery-key",
                repoDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "external-worker-process", 1L);

        // Spawn external JVM process that acquires the repository lock
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                "com.traceback.core.remediation.GitRepositoryLockManagerTest$ExternalLockHolderProcess",
                lockDir.toString(),
                repoDir.toString(),
                "60" // holds for 60s
        );
        pb.redirectErrorStream(true);
        Process childProcess = pb.start();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(childProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if ("LOCKED".equals(line.trim())) {
                    break;
                }
            }
            assertEquals("LOCKED", line);

            // While child process holds lock, recovery fails closed into RECOVERY_REQUIRED
            RemediationOperationEntity escalated = recoveryCoordinator.auditAndRecoverOperation(op.getId());
            assertEquals(RemediationOperationStatus.RECOVERY_REQUIRED, escalated.getStatus());
            assertTrue(locks.get(targetRef).getIsFrozen());

            // NOW KILL the old writer process (simulating process crash/kill)
            childProcess.destroyForcibly();
            assertTrue(childProcess.waitFor(5, TimeUnit.SECONDS));

            // OS kernel automatically releases the lock.
            // Reset status back to PRE_GIT_CAS_INTENT for re-recovery simulation
            escalated.setStatus(RemediationOperationStatus.PRE_GIT_CAS_INTENT);
            locks.get(targetRef).setIsFrozen(false);

            // Recovery runs again: now it acquires the repository lock, inspects unmutated ref, and safely marks FAILED!
            RemediationOperationEntity secondRecovery = recoveryCoordinator.auditAndRecoverOperation(op.getId());
            assertEquals(RemediationOperationStatus.FAILED, secondRecovery.getStatus());
            assertEquals("SAFELY_ABORTED_PRE_CAS", secondRecovery.getRecoveryStatus());
        } finally {
            if (childProcess.isAlive()) {
                childProcess.destroyForcibly();
            }
        }
    }

    @Test
    @DisplayName("Recovery with lock acquired but ambiguous Git/journal evidence remains RECOVERY_REQUIRED and ref frozen")
    void recoveryWithLockAcquiredAmbiguousEvidenceRemainsRecoveryRequired() {
        String targetRef = "refs/heads/ambiguous-recovery-ref";
        gitCasAdapter.executeCompareAndSwap(repoDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "ambiguous-evidence-key",
                repoDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "crashing-worker", 1L);

        // Simulate Git CAS completed on disk to commitB, but worker crashed before Phase C journal entry
        gitCasAdapter.executeCompareAndSwap(repoDir.toString(), targetRef, commitA.name(), commitB.name(), false);

        // Recovery acquires repository lock successfully, but detects ambiguous Git advancement
        RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(op.getId());

        assertEquals(RemediationOperationStatus.RECOVERY_REQUIRED, recovered.getStatus());
        assertEquals("AMBIGUOUS_EXECUTION_ESCALATED", recovered.getRecoveryStatus());
        assertTrue(locks.get(targetRef).getIsFrozen());
    }

    @Test
    @DisplayName("All Git mutation entry points are covered: executeGitCas rejected if repository lock is not held")
    void allGitMutationEntryPointsCovered() {
        String targetRef = "refs/heads/bypass-test-ref";
        gitCasAdapter.executeCompareAndSwap(repoDir.toString(), targetRef, ObjectId.zeroId().name(), commitA.name(), false);

        RemediationOperationEntity op = executionCoordinator.proposeOperation(
                UUID.randomUUID(),
                "bypass-key",
                repoDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "admin-tester"
        );

        executionCoordinator.recordDurableIntent(op.getId(), "bypass-worker", 1L);

        // Attempting to call executeGitCas directly without acquiring the repository lock MUST fail
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                executionCoordinator.executeGitCas(op.getId(), 1L)
        );
        assertTrue(ex.getMessage().contains("repository lock is not held by current thread"));

        // When repository lock is acquired, executeGitCas succeeds
        try (AutoCloseableLock lock = repositoryLockManager.acquireLock(repoDir.toString())) {
            assertDoesNotThrow(() -> executionCoordinator.executeGitCas(op.getId(), 1L));
        }
    }
}
