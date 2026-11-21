package com.traceback.core.investigation;

import com.traceback.core.agent.InvestigationAgentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * In-JVM concurrency-safe deduplication engine for TraceBack investigations.
 *
 * Implements a bounded 5-minute deduplication window for implicit investigation requests
 * (where no explicit investigation ID is provided by the client).
 *
 * Architecture & Concurrency Model:
 * 1. Completed durable investigations and agent reports are indexed by their SHA-256 request fingerprint.
 * 2. Reuse Qualification: An entry is reused ONLY if:
 *    - It completed within the configured window (default: 5 minutes / 300,000 ms).
 *    - It was persisted durably (`persistedDurable == true`).
 *    - Its persistence status is `"DURABLE"`.
 * 3. Never Reused: Non-durable (`persistedDurable == false`), `PERSISTENCE_FAILED`, or `EPHEMERAL`
 *    investigations are NEVER reused and will not prevent a fresh investigation from executing.
 * 4. In-Flight Coordination: When two identical requests arrive simultaneously within the JVM,
 *    an atomic `CompletableFuture` coordination lock ensures only one execution runs, while
 *    the concurrent follower waits up to 45 seconds to safely share the completed durable result.
 * 5. Deadlock & Stale State Prevention: In-flight entries are strictly removed upon completion or failure.
 *    If the leader fails or times out, followers fall back to independent execution without hanging.
 *
 * Cross-Instance / Durability Boundary:
 * This deduplicator operates strictly within the current JVM process. Cross-instance or restart-safe
 * deduplication requires a persistent database table (e.g. `investigation_request_fingerprints`)
 * and a schema migration, which is explicitly prohibited under current database freeze constraints.
 */
@Component
public class InvestigationDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(InvestigationDeduplicator.class);

    /** Default deduplication window: 5 minutes (300,000 milliseconds) */
    public static final long DEFAULT_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();

    /** In-flight follower wait timeout: 45 seconds */
    public static final long IN_FLIGHT_WAIT_SECONDS = 45L;

    private final long windowMillis;
    private final long waitTimeoutMillis;

    // Completed entries indexed by SHA-256 fingerprint
    private final ConcurrentHashMap<String, DeduplicationRecord> completedRecords = new ConcurrentHashMap<>();

    // In-flight coordination futures indexed by SHA-256 fingerprint
    private final ConcurrentHashMap<String, CompletableFuture<InvestigationResult>> inFlightInvestigations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<InvestigationAgentReport>> inFlightAgentReports = new ConcurrentHashMap<>();

    public InvestigationDeduplicator() {
        this(DEFAULT_WINDOW_MILLIS, Duration.ofSeconds(IN_FLIGHT_WAIT_SECONDS));
    }

    public InvestigationDeduplicator(long windowMillis) {
        this(windowMillis, Duration.ofSeconds(IN_FLIGHT_WAIT_SECONDS));
    }

    public InvestigationDeduplicator(long windowMillis, Duration waitTimeout) {
        this.windowMillis = windowMillis > 0 ? windowMillis : DEFAULT_WINDOW_MILLIS;
        this.waitTimeoutMillis = (waitTimeout != null && !waitTimeout.isNegative() && !waitTimeout.isZero())
                ? waitTimeout.toMillis()
                : Duration.ofSeconds(IN_FLIGHT_WAIT_SECONDS).toMillis();
    }

    public InvestigationDeduplicator(long windowMillis, long waitTimeout, TimeUnit unit) {
        this(windowMillis, unit != null ? Duration.ofMillis(unit.toMillis(waitTimeout)) : Duration.ofSeconds(IN_FLIGHT_WAIT_SECONDS));
    }

    public static InvestigationDeduplicator withWaitTimeoutSeconds(long windowMillis, long waitTimeoutSeconds) {
        return new InvestigationDeduplicator(windowMillis, Duration.ofSeconds(waitTimeoutSeconds));
    }

    public static InvestigationDeduplicator withWaitTimeoutMillis(long windowMillis, long waitTimeoutMillis) {
        return new InvestigationDeduplicator(windowMillis, Duration.ofMillis(waitTimeoutMillis));
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public long getWaitTimeoutMillis() {
        return waitTimeoutMillis;
    }

    public long getWaitTimeoutSeconds() {
        return Math.max(1, waitTimeoutMillis / 1000L);
    }

    // =========================================================================
    // Deduplication Outcome
    // =========================================================================

    /**
     * Encapsulates the execution result and whether it was deduplicated.
     */
    public record DeduplicationOutcome<T>(T value, boolean deduplicated) {}

    // =========================================================================
    // Deterministic Pipeline Deduplication
    // =========================================================================

    /**
     * Looks up an existing completed, durable investigation for the given fingerprint within the window.
     */
    public Optional<InvestigationResult> findReusableInvestigation(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }

        DeduplicationRecord record = completedRecords.get(fingerprint);
        if (record == null) {
            return Optional.empty();
        }

        if (record.isExpired(windowMillis)) {
            completedRecords.remove(fingerprint, record);
            return Optional.empty();
        }

        if (!record.isDurable() || !"DURABLE".equalsIgnoreCase(record.getPersistenceStatus())) {
            completedRecords.remove(fingerprint, record);
            return Optional.empty();
        }

        if (record.getResult() == null) {
            return Optional.empty();
        }

        return Optional.of(record.getResult());
    }

    /**
     * Executes a deterministic investigation with in-flight deduplication and leader/follower coordination.
     */
    public InvestigationResult executeCoordinatedInvestigation(String fingerprint, Supplier<InvestigationResult> supplier) {
        return executeInvestigationWithOutcome(fingerprint, supplier).value();
    }

    /**
     * Executes a deterministic investigation returning the outcome and deduplication status.
     */
    public DeduplicationOutcome<InvestigationResult> executeInvestigationWithOutcome(String fingerprint, Supplier<InvestigationResult> supplier) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return new DeduplicationOutcome<>(supplier.get(), false);
        }

        // 1. Fast-path: check completed cache
        Optional<InvestigationResult> existing = findReusableInvestigation(fingerprint);
        if (existing.isPresent()) {
            log.info("Deduplication HIT (completed): Reusing durable investigation {} for fingerprint {}",
                    existing.get().investigationId(), fingerprint);
            return new DeduplicationOutcome<>(existing.get(), true);
        }

        // 2. In-flight coordination with explicit leader election
        CompletableFuture<InvestigationResult> myFuture = new CompletableFuture<>();
        CompletableFuture<InvestigationResult> existingFuture = inFlightInvestigations.putIfAbsent(fingerprint, myFuture);
        boolean isLeader = (existingFuture == null);
        CompletableFuture<InvestigationResult> future = isLeader ? myFuture : existingFuture;

        if (isLeader) {
            try {
                InvestigationResult result = supplier.get();

                // Only cache if persisted durably
                if (result != null && result.persistedDurable() && "DURABLE".equalsIgnoreCase(result.persistenceStatus())) {
                    completedRecords.put(fingerprint, new DeduplicationRecord(fingerprint, result));
                    log.info("Deduplication STORE: Cached durable investigation {} for fingerprint {}",
                            result.investigationId(), fingerprint);
                } else if (result != null) {
                    log.warn("Deduplication SKIP: Investigation {} has status {} (persistedDurable={}); will not cache for reuse",
                            result.investigationId(), result.persistenceStatus(), result.persistedDurable());
                }

                future.complete(result);
                return new DeduplicationOutcome<>(result, false);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t;
            } finally {
                inFlightInvestigations.remove(fingerprint, future);
            }
        } else {
            // Concurrent follower: wait for leader
            log.info("Deduplication FOLLOWER: Awaiting in-flight investigation for fingerprint {}", fingerprint);
            try {
                InvestigationResult result = future.get(waitTimeoutMillis, TimeUnit.MILLISECONDS);
                if (result != null && result.persistedDurable() && "DURABLE".equalsIgnoreCase(result.persistenceStatus())) {
                    log.info("Deduplication FOLLOWER HIT: Shared leader investigation {} for fingerprint {}",
                            result.investigationId(), fingerprint);
                    return new DeduplicationOutcome<>(result, true);
                }
                log.warn("Leader produced non-durable or null result; follower executing independently for fingerprint {}", fingerprint);
                return new DeduplicationOutcome<>(supplier.get(), false);
            } catch (TimeoutException te) {
                long timeoutSecs = Math.max(1, waitTimeoutMillis / 1000L);
                log.warn("Follower timed out awaiting in-flight investigation for fingerprint: {} after {}ms", fingerprint, waitTimeoutMillis);
                throw new DeduplicationWaitTimeoutException(fingerprint, timeoutSecs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Follower thread interrupted awaiting in-flight investigation for fingerprint: {}", fingerprint);
                throw new DeduplicationInterruptedException("Follower thread interrupted awaiting in-flight investigation for fingerprint: " + fingerprint, ie);
            } catch (ExecutionException ee) {
                log.warn("Leader failed with exception ({}); follower executing independently for fingerprint {}",
                        ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage(), fingerprint);
                return new DeduplicationOutcome<>(supplier.get(), false);
            } catch (Exception e) {
                log.warn("Follower error awaiting in-flight investigation for fingerprint {} ({}); executing independently",
                        fingerprint, e.getMessage());
                return new DeduplicationOutcome<>(supplier.get(), false);
            }
        }
    }

    /**
     * Records a completed deterministic investigation into the deduplication store if durable.
     */
    public void recordCompletedInvestigation(String fingerprint, InvestigationResult result) {
        if (fingerprint == null || result == null) {
            return;
        }
        if (result.persistedDurable() && "DURABLE".equalsIgnoreCase(result.persistenceStatus())) {
            completedRecords.put(fingerprint, new DeduplicationRecord(fingerprint, result));
        }
    }

    // =========================================================================
    // Agent Pipeline Deduplication
    // =========================================================================

    /**
     * Looks up an existing completed, durable agent report for the given fingerprint within the window.
     */
    public Optional<InvestigationAgentReport> findReusableAgentReport(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }

        DeduplicationRecord record = completedRecords.get(fingerprint);
        if (record == null) {
            return Optional.empty();
        }

        if (record.isExpired(windowMillis)) {
            completedRecords.remove(fingerprint, record);
            return Optional.empty();
        }

        if (!record.isDurable() || !"DURABLE".equalsIgnoreCase(record.getPersistenceStatus())) {
            completedRecords.remove(fingerprint, record);
            return Optional.empty();
        }

        if (record.getAgentReport() == null) {
            return Optional.empty();
        }

        return Optional.of(record.getAgentReport());
    }

    /**
     * Executes an agent investigation with in-flight deduplication and leader/follower coordination.
     */
    public InvestigationAgentReport executeCoordinatedAgentReport(String fingerprint, Supplier<InvestigationAgentReport> supplier) {
        return executeAgentReportWithOutcome(fingerprint, supplier).value();
    }

    /**
     * Executes an agent investigation returning the outcome and deduplication status.
     */
    public DeduplicationOutcome<InvestigationAgentReport> executeAgentReportWithOutcome(String fingerprint, Supplier<InvestigationAgentReport> supplier) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return new DeduplicationOutcome<>(supplier.get(), false);
        }

        // 1. Fast-path check
        Optional<InvestigationAgentReport> existing = findReusableAgentReport(fingerprint);
        if (existing.isPresent()) {
            log.info("Agent Deduplication HIT: Reusing durable report {} (inv: {}) for fingerprint {}",
                    existing.get().reportId(), existing.get().investigationId(), fingerprint);
            return new DeduplicationOutcome<>(existing.get(), true);
        }

        // 2. In-flight coordination with explicit leader election
        CompletableFuture<InvestigationAgentReport> myFuture = new CompletableFuture<>();
        CompletableFuture<InvestigationAgentReport> existingFuture = inFlightAgentReports.putIfAbsent(fingerprint, myFuture);
        boolean isLeader = (existingFuture == null);
        CompletableFuture<InvestigationAgentReport> future = isLeader ? myFuture : existingFuture;

        if (isLeader) {
            try {
                InvestigationAgentReport report = supplier.get();

                if (report != null && report.persistedDurable() && "DURABLE".equalsIgnoreCase(report.persistenceStatus())) {
                    completedRecords.compute(fingerprint, (k, old) -> old != null ? old.withAgentReport(report) : new DeduplicationRecord(fingerprint, report));
                    log.info("Agent Deduplication STORE: Cached durable report {} for fingerprint {}",
                            report.reportId(), fingerprint);
                } else if (report != null) {
                    log.warn("Agent Deduplication SKIP: Report {} has status {} (persistedDurable={}); will not cache for reuse",
                            report.reportId(), report.persistenceStatus(), report.persistedDurable());
                }

                future.complete(report);
                return new DeduplicationOutcome<>(report, false);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t;
            } finally {
                inFlightAgentReports.remove(fingerprint, future);
            }
        } else {
            // Concurrent follower
            log.info("Agent Deduplication FOLLOWER: Awaiting in-flight agent report for fingerprint {}", fingerprint);
            try {
                InvestigationAgentReport report = future.get(waitTimeoutMillis, TimeUnit.MILLISECONDS);
                if (report != null && report.persistedDurable() && "DURABLE".equalsIgnoreCase(report.persistenceStatus())) {
                    log.info("Agent Deduplication FOLLOWER HIT: Shared leader report {} for fingerprint {}",
                            report.reportId(), fingerprint);
                    return new DeduplicationOutcome<>(report, true);
                }
                log.warn("Leader produced non-durable or null report; follower executing independently for fingerprint {}", fingerprint);
                return new DeduplicationOutcome<>(supplier.get(), false);
            } catch (TimeoutException te) {
                long timeoutSecs = Math.max(1, waitTimeoutMillis / 1000L);
                log.warn("Follower timed out awaiting in-flight agent report for fingerprint: {} after {}ms", fingerprint, waitTimeoutMillis);
                throw new DeduplicationWaitTimeoutException(fingerprint, timeoutSecs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Follower thread interrupted awaiting in-flight agent report for fingerprint: {}", fingerprint);
                throw new DeduplicationInterruptedException("Follower thread interrupted awaiting in-flight agent report for fingerprint: " + fingerprint, ie);
            } catch (ExecutionException ee) {
                log.warn("Leader failed with exception ({}); follower executing independently for fingerprint {}",
                        ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage(), fingerprint);
                return new DeduplicationOutcome<>(supplier.get(), false);
            } catch (Exception e) {
                log.warn("Follower error awaiting in-flight agent report for fingerprint {} ({}); executing independently",
                        fingerprint, e.getMessage());
                return new DeduplicationOutcome<>(supplier.get(), false);
            }
        }
    }

    /**
     * Records a completed agent report into the deduplication store if durable.
     */
    public void recordCompletedAgentReport(String fingerprint, InvestigationAgentReport report) {
        if (fingerprint == null || report == null) {
            return;
        }
        if (report.persistedDurable() && "DURABLE".equalsIgnoreCase(report.persistenceStatus())) {
            completedRecords.compute(fingerprint, (k, old) -> old != null ? old.withAgentReport(report) : new DeduplicationRecord(fingerprint, report));
        }
    }

    // =========================================================================
    // Maintenance & Inspection
    // =========================================================================

    /**
     * Evicts all completed entries whose age exceeds the configured window.
     * Uses atomic conditional removal to ensure that concurrent updates to the same
     * fingerprint are never erroneously evicted.
     * Active in-flight investigations (!future.isDone()) are never removed.
     */
    public int cleanExpiredEntries() {
        int removed = 0;
        for (Map.Entry<String, DeduplicationRecord> entry : completedRecords.entrySet()) {
            DeduplicationRecord record = entry.getValue();
            if (record != null && record.isExpired(windowMillis)) {
                // Atomic conditional remove guarantees we only remove this exact expired record
                if (completedRecords.remove(entry.getKey(), record)) {
                    removed++;
                }
            }
        }

        // Clean only completed or canceled in-flight futures; NEVER remove active in-flight investigations
        inFlightInvestigations.entrySet().removeIf(e -> e.getValue().isDone());
        inFlightAgentReports.entrySet().removeIf(e -> e.getValue().isDone());

        return removed;
    }

    /**
     * Periodic scheduled cleanup executing at a 5-minute interval (300,000 ms).
     * Exceptions are caught and logged so they cannot crash the scheduling executor or application.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 300000, initialDelay = 300000)
    public void scheduledCleanup() {
        try {
            int evicted = cleanExpiredEntries();
            if (evicted > 0) {
                log.info("Scheduled deduplication cache cleanup evicted {} expired record(s)", evicted);
            }
        } catch (Exception e) {
            log.error("Unexpected error during scheduled deduplication cache cleanup: {}", e.getMessage(), e);
        }
    }

    public void clear() {
        completedRecords.clear();
        inFlightInvestigations.clear();
        inFlightAgentReports.clear();
    }

    public int getCompletedCount() {
        return completedRecords.size();
    }

    public int getInFlightCount() {
        return inFlightInvestigations.size() + inFlightAgentReports.size();
    }

    public int getInFlightInvestigationCount() {
        return inFlightInvestigations.size();
    }

    public int getInFlightAgentReportCount() {
        return inFlightAgentReports.size();
    }

    // =========================================================================
    // Internal Record
    // =========================================================================

    public static final class DeduplicationRecord {
        private final String fingerprint;
        private final UUID investigationId;
        private final Instant completedAt;
        private final boolean durable;
        private final String persistenceStatus;
        private final InvestigationResult result;
        private final InvestigationAgentReport agentReport;

        public DeduplicationRecord(String fingerprint, InvestigationResult result) {
            this.fingerprint = fingerprint;
            this.investigationId = result.investigationId();
            this.completedAt = result.completedAt() != null ? result.completedAt() : Instant.now();
            this.durable = result.persistedDurable();
            this.persistenceStatus = result.persistenceStatus();
            this.result = result;
            this.agentReport = null;
        }

        public DeduplicationRecord(String fingerprint, InvestigationAgentReport report) {
            this.fingerprint = fingerprint;
            this.investigationId = report.investigationId();
            this.completedAt = report.generatedAt() != null ? report.generatedAt() : Instant.now();
            this.durable = report.persistedDurable();
            this.persistenceStatus = report.persistenceStatus();
            this.result = null;
            this.agentReport = report;
        }

        private DeduplicationRecord(String fingerprint, UUID investigationId, Instant completedAt,
                                    boolean durable, String persistenceStatus,
                                    InvestigationResult result, InvestigationAgentReport agentReport) {
            this.fingerprint = fingerprint;
            this.investigationId = investigationId;
            this.completedAt = completedAt;
            this.durable = durable;
            this.persistenceStatus = persistenceStatus;
            this.result = result;
            this.agentReport = agentReport;
        }

        public DeduplicationRecord withAgentReport(InvestigationAgentReport report) {
            return new DeduplicationRecord(
                    fingerprint,
                    report.investigationId() != null ? report.investigationId() : this.investigationId,
                    report.generatedAt() != null ? report.generatedAt() : this.completedAt,
                    report.persistedDurable(),
                    report.persistenceStatus(),
                    this.result,
                    report
            );
        }

        public boolean isExpired(long windowMillis) {
            return completedAt.plusMillis(windowMillis).isBefore(Instant.now());
        }

        public String getFingerprint() { return fingerprint; }
        public UUID getInvestigationId() { return investigationId; }
        public Instant getCompletedAt() { return completedAt; }
        public boolean isDurable() { return durable; }
        public String getPersistenceStatus() { return persistenceStatus; }
        public InvestigationResult getResult() { return result; }
        public InvestigationAgentReport getAgentReport() { return agentReport; }
    }
}
