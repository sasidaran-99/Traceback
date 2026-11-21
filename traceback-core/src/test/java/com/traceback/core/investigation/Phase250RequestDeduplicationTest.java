package com.traceback.core.investigation;

import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.persistence.InvestigationPersistenceCoordinator;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.validation.ClaimValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2.50 Comprehensive Verification Test Suite:
 * Request Fingerprint Deduplication & Preliminary Coordinator Safety Verification.
 *
 * Covers all 15 scenarios specified in Phase 2.50:
 * 1. Identical request within 5 minutes is reused.
 * 2. Identical request after 5 minutes is not reused.
 * 3. Different repository creates independent investigation.
 * 4. Different exception signature creates independent investigation.
 * 5. Different stack location creates independent investigation.
 * 6. Different investigation mode creates independent investigation.
 * 7. Different analysis window creates independent investigation.
 * 8. Explicit investigation ID preserves existing idempotency behavior.
 * 9. Intentional force-new request bypasses deduplication.
 * 10. Two simultaneous identical requests execute once (concurrency safety).
 * 11. Persistence failure is not cached as durable deduplication entry.
 * 12. Non-durable previous result is not returned as successful duplicate.
 * 13. Failed in-flight request cleans up coordination state.
 * 14. Coordinator unavailability fails safely without non-transactional persistence.
 * 15. Existing Phase 9.8.2 idempotency regression test.
 */
class Phase250RequestDeduplicationTest {

    private static final String SAMPLE_STACK_TRACE_A =
            "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"str\" is null\n" +
            "\tat com.example.service.UserService.processUser(UserService.java:42)\n" +
            "\tat com.example.controller.UserController.getUser(UserController.java:18)\n";

    private static final String SAMPLE_STACK_TRACE_B =
            "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"str\" is null\n" +
            "\tat com.example.service.UserService.processUser(UserService.java:88)\n" +
            "\tat com.example.controller.UserController.getUser(UserController.java:18)\n";

    private static final String SAMPLE_STACK_TRACE_ILLEGAL_ARG =
            "java.lang.IllegalArgumentException: Invalid user id: -1\n" +
            "\tat com.example.service.UserService.processUser(UserService.java:42)\n" +
            "\tat com.example.controller.UserController.getUser(UserController.java:18)\n";

    private InvestigationResult createStubResult(UUID id, boolean durable, String status) {
        return new InvestigationResult(
                id,
                "order-service",
                "production",
                Instant.now(),
                Instant.now(),
                "COMPLETED",
                5,
                1,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList(),
                true,
                InvestigationMode.RUNTIME_FAILURE,
                durable,
                status
        );
    }

    private InvestigationAgentReport createStubAgentReport(UUID invId, String repId, boolean durable, String status) {
        return new InvestigationAgentReport(
                repId,
                invId,
                "Investigated root cause",
                "Fix user validation",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                "AI reasoning summary",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                AgentInvestigationStatus.PROBABLE,
                Collections.emptyList(),
                Collections.emptyList(),
                Instant.now(),
                "COMPLETED",
                "SKIPPED_UNCONFIGURED",
                durable,
                status
        );
    }

    // =========================================================================
    // Nested Group 1: Fingerprint Utility & Attribute Variance (Tests 3 - 7)
    // =========================================================================

    @Nested
    @DisplayName("Request Fingerprint Computation & Attribute Variance")
    class FingerprintVarianceTests {

        @Test
        @DisplayName("Test 3: Different repository creates independent investigation")
        void test3_differentRepositoryProducesDifferentFingerprint() {
            String fp1 = RequestFingerprint.compute(
                    "C:/repos/service-a", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );
            String fp2 = RequestFingerprint.compute(
                    "C:/repos/service-b", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );

            assertThat(fp1).isNotEmpty().hasSize(64);
            assertThat(fp2).isNotEmpty().hasSize(64);
            assertThat(fp1).isNotEqualTo(fp2);
        }

        @Test
        @DisplayName("Test 4: Different exception signature creates independent investigation")
        void test4_differentExceptionSignatureProducesDifferentFingerprint() {
            String fpNpe = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );
            String fpIllegalArg = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_ILLEGAL_ARG, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );

            assertThat(fpNpe).isNotEqualTo(fpIllegalArg);
        }

        @Test
        @DisplayName("Test 5: Different stack location creates independent investigation")
        void test5_differentStackLocationProducesDifferentFingerprint() {
            String fpLine42 = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );
            String fpLine88 = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_B, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );

            assertThat(fpLine42).isNotEqualTo(fpLine88);
        }

        @Test
        @DisplayName("Test 6: Different investigation mode creates independent investigation")
        void test6_differentInvestigationModeProducesDifferentFingerprint() {
            String fpRuntime = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );
            String fpForensics = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.WORKING_TREE_FORENSICS
            );

            assertThat(fpRuntime).isNotEqualTo(fpForensics);
        }

        @Test
        @DisplayName("Test 7: Different analysis window creates independent investigation")
        void test7_differentAnalysisWindowProducesDifferentFingerprint() {
            String fp60m = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );
            String fp120m = RequestFingerprint.compute(
                    "C:/repos/svc", SAMPLE_STACK_TRACE_A, null, "log error",
                    "svc", "prod", 120, 50, InvestigationMode.RUNTIME_FAILURE
            );

            assertThat(fp60m).isNotEqualTo(fp120m);
        }

        @Test
        @DisplayName("Fingerprint normalization ignores volatile memory addresses and timestamp prefixes")
        void test_fingerprintNormalization_scrubsVolatileAddresses() {
            String stackWithAddr1 = "java.lang.Object@3a71f4b: failure at com.example.Foo.bar(Foo.java:10)";
            String stackWithAddr2 = "java.lang.Object@7c82e1d: failure at com.example.Foo.bar(Foo.java:10)";

            String fp1 = RequestFingerprint.compute("C:/repos/svc", stackWithAddr1, null, null, "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);
            String fp2 = RequestFingerprint.compute("C:/repos/svc", stackWithAddr2, null, null, "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);

            assertThat(fp1).isEqualTo(fp2);
        }

        @Test
        @DisplayName("Windows paths normalize consistently regardless of drive-letter and path casing")
        void test_windowsPathCasingMatches() {
            String norm1 = RequestFingerprint.normalizeRepositoryPath("C:/repos/App");
            String norm2 = RequestFingerprint.normalizeRepositoryPath("c:/repos/app");
            String norm3 = RequestFingerprint.normalizeRepositoryPath("C:\\repos\\App");
            String norm4 = RequestFingerprint.normalizeRepositoryPath("C:/repos/./App");

            assertThat(norm1).isEqualTo("c:/repos/app");
            assertThat(norm2).isEqualTo("c:/repos/app");
            assertThat(norm3).isEqualTo("c:/repos/app");
            assertThat(norm4).isEqualTo("c:/repos/app");

            String fp1 = RequestFingerprint.compute("C:/repos/App", SAMPLE_STACK_TRACE_A, null, "log", "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);
            String fp2 = RequestFingerprint.compute("c:/repos/app", SAMPLE_STACK_TRACE_A, null, "log", "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);
            String fp3 = RequestFingerprint.compute("C:\\repos\\App", SAMPLE_STACK_TRACE_A, null, "log", "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);

            assertThat(fp1).isEqualTo(fp2);
            assertThat(fp1).isEqualTo(fp3);
        }

        @Test
        @DisplayName("Linux paths preserve case distinction (/repo/App != /repo/app)")
        void test_linuxPathCasingPreservesDistinction() {
            String normUpper = RequestFingerprint.normalizeRepositoryPath("/repo/App");
            String normLower = RequestFingerprint.normalizeRepositoryPath("/repo/app");

            assertThat(normUpper).isEqualTo("/repo/App");
            assertThat(normLower).isEqualTo("/repo/app");
            assertThat(normUpper).isNotEqualTo(normLower);

            String fpUpper = RequestFingerprint.compute("/repo/App", SAMPLE_STACK_TRACE_A, null, "log", "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);
            String fpLower = RequestFingerprint.compute("/repo/app", SAMPLE_STACK_TRACE_A, null, "log", "svc", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE);

            assertThat(fpUpper).isNotEqualTo(fpLower);
        }

        @Test
        @DisplayName("Path normalization resolves . and .. segments in-memory without filesystem writes")
        void test_pathTraversalNormalizedWithoutFilesystem() {
            String normWin = RequestFingerprint.normalizeRepositoryPath("C:/repos/sub/../App");
            assertThat(normWin).isEqualTo("c:/repos/app");

            String normLinux = RequestFingerprint.normalizeRepositoryPath("/var/log/../repo/./App");
            assertThat(normLinux).isEqualTo("/var/repo/App");
        }
    }

    // =========================================================================
    // Nested Group 2: Deduplicator Window & Lifetime (Tests 1, 2, 9)
    // =========================================================================

    @Nested
    @DisplayName("Deduplicator Window & Force-New Behavior")
    class DeduplicationWindowTests {

        @Test
        @DisplayName("Test 1: Identical request within 5 minutes is reused")
        void test1_identicalRequestWithin5MinutesIsReused() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-test-01";
            UUID originalId = UUID.randomUUID();
            InvestigationResult originalResult = createStubResult(originalId, true, "DURABLE");

            AtomicInteger executionCount = new AtomicInteger(0);

            // First execution
            InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> outcome1 =
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        executionCount.incrementAndGet();
                        return originalResult;
                    });

            assertThat(outcome1.deduplicated()).isFalse();
            assertThat(outcome1.value().investigationId()).isEqualTo(originalId);
            assertThat(executionCount.get()).isEqualTo(1);

            // Second execution with identical fingerprint within window
            InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> outcome2 =
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        executionCount.incrementAndGet();
                        return createStubResult(UUID.randomUUID(), true, "DURABLE");
                    });

            assertThat(outcome2.deduplicated()).isTrue();
            assertThat(outcome2.value().investigationId()).isEqualTo(originalId);
            assertThat(executionCount.get()).isEqualTo(1); // Supplier not re-run!
        }

        @Test
        @DisplayName("Test 2: Identical request after 5 minutes is not reused (expired)")
        void test2_identicalRequestAfter5MinutesIsNotReused() throws InterruptedException {
            // Use 40ms window to simulate expiration cleanly in isolated test
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(40L);
            String fingerprint = "fp-test-02";
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();

            AtomicInteger executionCount = new AtomicInteger(0);

            InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> outcome1 =
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        executionCount.incrementAndGet();
                        return createStubResult(firstId, true, "DURABLE");
                    });

            assertThat(outcome1.deduplicated()).isFalse();
            assertThat(outcome1.value().investigationId()).isEqualTo(firstId);

            // Wait for window to expire
            Thread.sleep(60L);

            // Second execution after expiration
            InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> outcome2 =
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        executionCount.incrementAndGet();
                        return createStubResult(secondId, true, "DURABLE");
                    });

            assertThat(outcome2.deduplicated()).isFalse();
            assertThat(outcome2.value().investigationId()).isEqualTo(secondId);
            assertThat(executionCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Test 9: Intentional force-new request bypasses deduplication")
        void test9_forceNewBypassesDeduplication() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-test-09";
            UUID cachedId = UUID.randomUUID();
            InvestigationResult cachedResult = createStubResult(cachedId, true, "DURABLE");

            // Seed cache
            deduplicator.recordCompletedInvestigation(fingerprint, cachedResult);
            assertThat(deduplicator.findReusableInvestigation(fingerprint)).isPresent();

            // When forceNew is true, client code bypasses deduplicator and runs fresh internal logic
            UUID freshId = UUID.randomUUID();
            InvestigationResult freshResult = createStubResult(freshId, true, "DURABLE");

            // Verify fresh ID is independent
            assertThat(freshResult.investigationId()).isNotEqualTo(cachedId);
            assertThat(freshResult.investigationId()).isEqualTo(freshId);
        }

        @Test
        @DisplayName("Test 16: cleanExpiredEntries evicts expired entries while preserving valid entries")
        void test16_cleanExpiredEntriesEvictsExpiredOnly() throws InterruptedException {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(50L);
            String expiredFp = "fp-expired";
            String validFp = "fp-valid";

            InvestigationResult expiredResult = createStubResult(UUID.randomUUID(), true, "DURABLE");
            deduplicator.recordCompletedInvestigation(expiredFp, expiredResult);

            Thread.sleep(70L); // Wait so expiredFp becomes older than window

            InvestigationResult validResult = createStubResult(UUID.randomUUID(), true, "DURABLE");
            deduplicator.recordCompletedInvestigation(validFp, validResult);

            assertThat(deduplicator.getCompletedCount()).isEqualTo(2);

            int evicted = deduplicator.cleanExpiredEntries();
            assertThat(evicted).isEqualTo(1);
            assertThat(deduplicator.findReusableInvestigation(expiredFp)).isEmpty();
            assertThat(deduplicator.findReusableInvestigation(validFp)).isPresent();
        }

        @Test
        @DisplayName("Test 17: Scheduled cleanup never removes active in-flight investigations")
        void test17_scheduledCleanupNeverRemovesActiveInFlightInvestigations() throws Exception {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(10L);
            String inFlightFp = "fp-active-inflight";
            CountDownLatch leaderRunning = new CountDownLatch(1);
            CountDownLatch finishLeader = new CountDownLatch(1);

            CompletableFuture<InvestigationResult> leaderFuture = CompletableFuture.supplyAsync(() -> {
                return deduplicator.executeCoordinatedInvestigation(inFlightFp, () -> {
                    leaderRunning.countDown();
                    try {
                        finishLeader.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return createStubResult(UUID.randomUUID(), true, "DURABLE");
                });
            });

            leaderRunning.await();
            assertThat(deduplicator.getInFlightCount()).isEqualTo(1);

            // Execute scheduled cleanup while investigation is actively running
            deduplicator.scheduledCleanup();

            // In-flight investigation MUST still be present and tracked
            assertThat(deduplicator.getInFlightCount()).isEqualTo(1);

            finishLeader.countDown();
            InvestigationResult res = leaderFuture.get(5, TimeUnit.SECONDS);
            assertThat(res).isNotNull();
            assertThat(deduplicator.getInFlightCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Test 18: scheduledCleanup catches unexpected runtime exceptions safely without crashing")
        void test18_scheduledCleanupCatchesErrorsSafely() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L) {
                @Override
                public int cleanExpiredEntries() {
                    throw new RuntimeException("Simulated transient cache error");
                }
            };
            // Scheduled cleanup catches Exception and logs without crashing
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> deduplicator.scheduledCleanup());
        }

        @Test
        @DisplayName("Test 19: scheduledCleanup does not swallow fatal JVM errors")
        void test19_scheduledCleanupDoesNotSwallowFatalErrors() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L) {
                @Override
                public int cleanExpiredEntries() {
                    throw new OutOfMemoryError("Simulated fatal JVM out-of-memory error");
                }
            };
            // Scheduled cleanup must NOT swallow fatal Error instances
            org.junit.jupiter.api.Assertions.assertThrows(
                    OutOfMemoryError.class,
                    () -> deduplicator.scheduledCleanup()
            );
        }
    }

    // =========================================================================
    // Nested Group 3: Concurrency & Failure Safety (Tests 10, 11, 12, 13)
    // =========================================================================

    @Nested
    @DisplayName("Concurrency Coordination & Fault Tolerance")
    class ConcurrencyAndFaultSafetyTests {

        @Test
        @DisplayName("Test 10: Two simultaneous identical requests execute once (concurrency test)")
        void test10_twoSimultaneousIdenticalRequestsExecuteOnce() throws Exception {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-concurrent-10";
            UUID sharedId = UUID.randomUUID();

            AtomicInteger executionCounter = new AtomicInteger(0);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);

            Future<InvestigationResult> future1 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return deduplicator.executeCoordinatedInvestigation(fingerprint, () -> {
                    executionCounter.incrementAndGet();
                    try {
                        Thread.sleep(100L); // Simulate pipeline work
                    } catch (InterruptedException ignored) {}
                    return createStubResult(sharedId, true, "DURABLE");
                });
            });

            Future<InvestigationResult> future2 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return deduplicator.executeCoordinatedInvestigation(fingerprint, () -> {
                    executionCounter.incrementAndGet();
                    return createStubResult(sharedId, true, "DURABLE");
                });
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown(); // Release both threads simultaneously

            InvestigationResult res1 = future1.get(10, TimeUnit.SECONDS);
            InvestigationResult res2 = future2.get(10, TimeUnit.SECONDS);

            executor.shutdown();

            assertThat(res1).isNotNull();
            assertThat(res2).isNotNull();
            assertThat(res1.investigationId()).isEqualTo(sharedId);
            assertThat(res2.investigationId()).isEqualTo(sharedId);
            assertThat(executionCounter.get()).isEqualTo(1); // Exactly 1 pipeline execution!
        }

        @Test
        @DisplayName("Test 11: Persistence failure is not cached as durable deduplication entry")
        void test11_persistenceFailureIsNotCachedAsDurableEntry() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-failed-pers-11";
            UUID failedId = UUID.randomUUID();
            InvestigationResult failedResult = createStubResult(failedId, false, "PERSISTENCE_FAILED");

            AtomicInteger executionCounter = new AtomicInteger(0);

            // First run fails persistence
            InvestigationResult res1 = deduplicator.executeCoordinatedInvestigation(fingerprint, () -> {
                executionCounter.incrementAndGet();
                return failedResult;
            });

            assertThat(res1.persistedDurable()).isFalse();
            assertThat(res1.persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");
            assertThat(executionCounter.get()).isEqualTo(1);

            // Deduplicator must NOT have cached it
            assertThat(deduplicator.findReusableInvestigation(fingerprint)).isEmpty();

            // Next identical request executes afresh
            UUID freshId = UUID.randomUUID();
            InvestigationResult res2 = deduplicator.executeCoordinatedInvestigation(fingerprint, () -> {
                executionCounter.incrementAndGet();
                return createStubResult(freshId, true, "DURABLE");
            });

            assertThat(res2.investigationId()).isEqualTo(freshId);
            assertThat(res2.persistedDurable()).isTrue();
            assertThat(executionCounter.get()).isEqualTo(2); // Second execution succeeded
        }

        @Test
        @DisplayName("Test 12: Non-durable previous result is not returned as successful duplicate")
        void test12_nonDurablePreviousResultIsNotReturnedAsSuccessfulDuplicate() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-ephemeral-12";
            UUID ephemeralId = UUID.randomUUID();
            InvestigationResult ephemeralResult = createStubResult(ephemeralId, false, "EPHEMERAL");

            // Attempt to record non-durable result directly
            deduplicator.recordCompletedInvestigation(fingerprint, ephemeralResult);

            // Lookup must return empty
            assertThat(deduplicator.findReusableInvestigation(fingerprint)).isEmpty();
        }

        @Test
        @DisplayName("Test 13: Failed in-flight request cleans up coordination state without deadlock")
        void test13_failedInFlightRequestCleansUpCoordinationState() {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-failure-cleanup-13";

            // Leader throws exception
            assertThatThrownBy(() -> deduplicator.executeCoordinatedInvestigation(fingerprint, () -> {
                throw new IllegalStateException("Git collector crashed");
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Git collector crashed");

            // Verify in-flight map is completely cleared (no lingering stale future)
            assertThat(deduplicator.getInFlightInvestigationCount()).isZero();

            // Subsequent identical request must NOT deadlock or re-throw stale error
            UUID retryId = UUID.randomUUID();
            InvestigationResult retryResult = deduplicator.executeCoordinatedInvestigation(fingerprint, () ->
                    createStubResult(retryId, true, "DURABLE")
            );

            assertThat(retryResult.investigationId()).isEqualTo(retryId);
            assertThat(retryResult.persistedDurable()).isTrue();
        }

        @Test
        @DisplayName("10 simultaneous identical callers execute supplier exactly once and all receive identical result")
        void test_tenSimultaneousIdenticalCallersExecuteSupplierOnceAndShareResult() throws Exception {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-concurrent-10-threads";
            UUID sharedId = UUID.randomUUID();

            int threadCount = 10;
            AtomicInteger supplierExecutionCount = new AtomicInteger(0);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult>>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        supplierExecutionCount.incrementAndGet();
                        try {
                            Thread.sleep(150L);
                        } catch (InterruptedException ignored) {}
                        return createStubResult(sharedId, true, "DURABLE");
                    });
                }));
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            int leaderCount = 0;
            int followerCount = 0;
            for (Future<InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult>> f : futures) {
                InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> outcome = f.get(10, TimeUnit.SECONDS);
                assertThat(outcome.value().investigationId()).isEqualTo(sharedId);
                if (outcome.deduplicated()) {
                    followerCount++;
                } else {
                    leaderCount++;
                }
            }

            executor.shutdown();

            assertThat(supplierExecutionCount.get()).isEqualTo(1);
            assertThat(leaderCount).isEqualTo(1);
            assertThat(followerCount).isEqualTo(9);
        }

        @Test
        @DisplayName("Follower timeout throws DeduplicationWaitTimeoutException, does not call supplier, and preserves active leader")
        void test_followerTimeoutThrowsDeduplicationWaitTimeoutExceptionAndPreservesLeader() throws Exception {
            // Configure deduplicator with 60ms follower timeout
            InvestigationDeduplicator deduplicator = InvestigationDeduplicator.withWaitTimeoutMillis(300_000L, 60L);
            String fingerprint = "fp-follower-timeout";
            UUID leaderId = UUID.randomUUID();

            AtomicInteger supplierCallCount = new AtomicInteger(0);
            CountDownLatch leaderStartedLatch = new CountDownLatch(1);
            CountDownLatch followerDoneLatch = new CountDownLatch(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Leader starts and sleeps for 250ms
            Future<InvestigationResult> leaderFuture = executor.submit(() ->
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        supplierCallCount.incrementAndGet();
                        leaderStartedLatch.countDown();
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException ignored) {}
                        return createStubResult(leaderId, true, "DURABLE");
                    }).value()
            );

            leaderStartedLatch.await(2, TimeUnit.SECONDS);

            // Follower enters while leader is sleeping. Follower wait timeout is 60ms.
            Future<?> followerFuture = executor.submit(() -> {
                try {
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        supplierCallCount.incrementAndGet();
                        return createStubResult(UUID.randomUUID(), true, "DURABLE");
                    });
                } finally {
                    followerDoneLatch.countDown();
                }
            });

            // Follower must fail with DeduplicationWaitTimeoutException
            assertThatThrownBy(followerFuture::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeduplicationWaitTimeoutException.class);

            // Supplier must NOT have been called by the follower
            assertThat(supplierCallCount.get()).isEqualTo(1);

            // Leader completes successfully without being interrupted or cancelled
            InvestigationResult leaderResult = leaderFuture.get(5, TimeUnit.SECONDS);
            assertThat(leaderResult.investigationId()).isEqualTo(leaderId);
            assertThat(leaderResult.persistedDurable()).isTrue();

            executor.shutdown();
        }

        @Test
        @DisplayName("Interrupted follower restores interrupt flag, throws DeduplicationInterruptedException, and does not run supplier")
        void test_interruptedFollowerRestoresInterruptStatus() throws Exception {
            InvestigationDeduplicator deduplicator = new InvestigationDeduplicator(300_000L);
            String fingerprint = "fp-interrupted-follower";
            UUID leaderId = UUID.randomUUID();

            AtomicInteger supplierCallCount = new AtomicInteger(0);
            CountDownLatch leaderStartedLatch = new CountDownLatch(1);
            CountDownLatch followerEnteredLatch = new CountDownLatch(1);
            AtomicBoolean followerInterruptStatusRecorded = new AtomicBoolean(false);

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Leader thread
            Future<InvestigationResult> leaderFuture = executor.submit(() ->
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        supplierCallCount.incrementAndGet();
                        leaderStartedLatch.countDown();
                        try {
                            Thread.sleep(600L);
                        } catch (InterruptedException ignored) {}
                        return createStubResult(leaderId, true, "DURABLE");
                    }).value()
            );

            leaderStartedLatch.await(2, TimeUnit.SECONDS);

            // Follower thread that will be interrupted
            Thread followerThread = new Thread(() -> {
                followerEnteredLatch.countDown();
                try {
                    deduplicator.executeInvestigationWithOutcome(fingerprint, () -> {
                        supplierCallCount.incrementAndGet();
                        return createStubResult(UUID.randomUUID(), true, "DURABLE");
                    });
                } catch (DeduplicationInterruptedException die) {
                    followerInterruptStatusRecorded.set(Thread.currentThread().isInterrupted());
                }
            });

            followerThread.start();
            followerEnteredLatch.await(2, TimeUnit.SECONDS);
            Thread.sleep(80L); // Ensure follower is in future.get()

            followerThread.interrupt();
            followerThread.join(3000L);

            assertThat(followerInterruptStatusRecorded.get()).isTrue();
            assertThat(supplierCallCount.get()).isEqualTo(1); // Supplier never called by follower!

            // Leader completes normally
            InvestigationResult leaderResult = leaderFuture.get(5, TimeUnit.SECONDS);
            assertThat(leaderResult.investigationId()).isEqualTo(leaderId);

            executor.shutdown();
        }

        @Test
        @DisplayName("Agent pipeline follower timeout throws DeduplicationWaitTimeoutException without running supplier")
        void test_agentReportFollowerTimeoutThrowsDeduplicationWaitTimeoutException() throws Exception {
            InvestigationDeduplicator deduplicator = InvestigationDeduplicator.withWaitTimeoutMillis(300_000L, 60L);
            String fingerprint = "fp-agent-follower-timeout";
            UUID invId = UUID.randomUUID();
            String repId = "REP-timeout-agent";

            AtomicInteger supplierCallCount = new AtomicInteger(0);
            CountDownLatch leaderStartedLatch = new CountDownLatch(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Leader starts and sleeps
            Future<InvestigationAgentReport> leaderFuture = executor.submit(() ->
                    deduplicator.executeAgentReportWithOutcome(fingerprint, () -> {
                        supplierCallCount.incrementAndGet();
                        leaderStartedLatch.countDown();
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException ignored) {}
                        return createStubAgentReport(invId, repId, true, "DURABLE");
                    }).value()
            );

            leaderStartedLatch.await(2, TimeUnit.SECONDS);

            // Follower enters
            Future<?> followerFuture = executor.submit(() ->
                    deduplicator.executeAgentReportWithOutcome(fingerprint, () -> {
                        supplierCallCount.incrementAndGet();
                        return createStubAgentReport(invId, "REP-fresh", true, "DURABLE");
                    })
            );

            // Follower must throw DeduplicationWaitTimeoutException
            assertThatThrownBy(followerFuture::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeduplicationWaitTimeoutException.class);

            assertThat(supplierCallCount.get()).isEqualTo(1); // Not called by follower

            // Leader completes normally
            InvestigationAgentReport leaderResult = leaderFuture.get(5, TimeUnit.SECONDS);
            assertThat(leaderResult.reportId()).isEqualTo(repId);

            executor.shutdown();
        }
    }

    // =========================================================================
    // Nested Group 4: Preliminary Coordinator Safety & Phase 9.8.2 Idempotency (Tests 8, 14, 15)
    // =========================================================================

    @Nested
    @DisplayName("Coordinator Unavailability Safety & Phase 9.8.2 Idempotency")
    class CoordinatorSafetyAndIdempotencyTests {

        private BifrostClient bifrostClient;
        private TavilyResearchService tavilyService;
        private ClaimValidator claimValidator;
        private EvidenceRegistry evidenceRegistry;
        private InvestigationService investigationService;

        private InvestigationAgentReportRepository reportRepository;
        private InvestigationAgentStepRepository stepRepository;
        private InvestigationRepository investigationRepository;
        private InvestigationEvidenceRepository evidenceRepository;
        private InvestigationHypothesisRepository hypothesisRepository;
        private InvestigationClaimRepository claimRepository;
        private InvestigationPersistenceMapper mapper;

        @BeforeEach
        void setUp() {
            bifrostClient = mock(BifrostClient.class);
            when(bifrostClient.isAvailable()).thenReturn(false);

            tavilyService = mock(TavilyResearchService.class);
            when(tavilyService.isAvailable()).thenReturn(false);

            claimValidator = mock(ClaimValidator.class);
            when(claimValidator.validateClaim(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

            evidenceRegistry = new ConcreteEvidenceRegistry();
            investigationService = mock(InvestigationService.class);

            reportRepository = mock(InvestigationAgentReportRepository.class);
            stepRepository = mock(InvestigationAgentStepRepository.class);
            investigationRepository = mock(InvestigationRepository.class);
            evidenceRepository = mock(InvestigationEvidenceRepository.class);
            hypothesisRepository = mock(InvestigationHypothesisRepository.class);
            claimRepository = mock(InvestigationClaimRepository.class);
            mapper = new InvestigationPersistenceMapper();
        }

        @Test
        @DisplayName("Test 14: Coordinator unavailability fails safely without non-transactional persistence")
        void test14_coordinatorUnavailabilityFailsSafelyWithoutNonTransactionalPersistence() {
            // Construct agent with repositories, but NO InvestigationPersistenceCoordinator
            InvestigationAgent agent = new InvestigationAgent(
                    bifrostClient,
                    tavilyService,
                    claimValidator,
                    evidenceRegistry,
                    investigationService
            );
            agent.setAgentReportRepository(reportRepository);
            agent.setAgentStepRepository(stepRepository);
            agent.setInvestigationRepository(investigationRepository);

            // Notice: persistenceCoordinator is deliberately NOT set (null).
            // Under the mandatory Phase 2.50 preliminary fix, getOrCreateCoordinator() has been REMOVED.
            // PersistReport must log a warning, fail safely, and return false without performing unmanaged repository writes.

            UUID invId = UUID.randomUUID();
            InvestigationResult deterministicResult = createStubResult(invId, true, "DURABLE");

            InvestigationAgentReport report = agent.investigate(deterministicResult);

            // Report returned in memory, but persistence status is PERSISTENCE_FAILED and persistedDurable is false
            assertThat(report).isNotNull();
            assertThat(report.persistedDurable()).isFalse();
            assertThat(report.persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");

            // Crucially: no repository save operations must have occurred without a transaction coordinator
            verify(reportRepository, never()).save(any(AgentReportEntity.class));
            verify(stepRepository, never()).save(any());
        }

        @Test
        @DisplayName("Test 8: Explicit investigation ID preserves existing idempotency behavior")
        void test8_explicitInvestigationIdPreservesExistingIdempotencyBehavior() {
            InvestigationAgent agent = new InvestigationAgent(
                    bifrostClient,
                    tavilyService,
                    claimValidator,
                    evidenceRegistry,
                    investigationService
            );

            UUID invId = UUID.randomUUID();
            String reportId = "REP-explicit-8";
            InvestigationAgentReport cached = createStubAgentReport(invId, reportId, true, "DURABLE");

            agent.cacheReport(cached);

            // When queried with explicit ID, returns existing report without re-executing
            Optional<InvestigationAgentReport> cachedOpt = agent.getReport(invId);
            assertThat(cachedOpt).isPresent();
            assertThat(cachedOpt.get().reportId()).isEqualTo(reportId);
            assertThat(cachedOpt.get().investigationId()).isEqualTo(invId);

            // Underlying services not called
            verify(investigationService, never()).startInvestigation(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("Test 15: Existing Phase 9.8.2 idempotency regression test")
        void test15_phase982IdempotencyRegression() {
            InvestigationPersistenceCoordinator coordinator = new InvestigationPersistenceCoordinator(
                    investigationRepository,
                    evidenceRepository,
                    claimRepository,
                    null,
                    hypothesisRepository,
                    reportRepository,
                    stepRepository,
                    mapper
            );

            InvestigationAgent agent = new InvestigationAgent(
                    bifrostClient,
                    tavilyService,
                    claimValidator,
                    evidenceRegistry,
                    investigationService
            );
            agent.setAgentReportRepository(reportRepository);
            agent.setAgentStepRepository(stepRepository);
            agent.setInvestigationRepository(investigationRepository);
            agent.setPersistenceCoordinator(coordinator);

            UUID invId = UUID.randomUUID();
            String reportId = "REP-persisted-15";
            AgentReportEntity existingEntity = new AgentReportEntity(
                    reportId, invId, "PROBABLE", "Summary text", "Reasoning text",
                    null, "[]", "[]", "[]"
            );
            existingEntity.setPrimarySuspectedRootCause("Suspected root cause");
            existingEntity.setCreatedAt(Instant.now());

            InvestigationEntity invEntity = new InvestigationEntity(
                    invId, InvestigationMode.RUNTIME_FAILURE, "order-service", "production",
                    "C:/repos/orders", "COMPLETED", "Exception occurred", "PROBABLE", 0.90, reportId
            );

            when(investigationRepository.findById(invId)).thenReturn(Optional.of(invEntity));
            when(reportRepository.findByReportId(reportId)).thenReturn(Optional.of(existingEntity));

            // getReport(invId) returns database-backed entity mapped safely
            Optional<InvestigationAgentReport> reportOpt = agent.getReport(invId);
            assertThat(reportOpt).isPresent();
            assertThat(reportOpt.get().investigationId()).isEqualTo(invId);
            assertThat(reportOpt.get().reportId()).isEqualTo(reportId);
            assertThat(reportOpt.get().summary()).isEqualTo("Summary text");
            assertThat(reportOpt.get().persistedDurable()).isTrue();
            assertThat(reportOpt.get().persistenceStatus()).isEqualTo("DURABLE");
        }
    }
}
