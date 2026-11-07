package com.traceback.core.correlation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceEngineTest {

    private CorrelationEngine engine;
    private EvidenceRegistry registry;
    private Incident incident;

    @BeforeEach
    void setUp() {
        engine = new CorrelationEngine();
        registry = new ConcreteEvidenceRegistry();
        incident = new Incident(
                UUID.randomUUID(),
                "INC-TEST",
                "Unit Test Incident",
                "payment-service",
                "production",
                "HIGH",
                "INVESTIGATING",
                Instant.parse("2026-10-10T14:16:30Z"),
                null,
                null,
                null
        );
    }

    @Test
    @DisplayName("Formula verification: S = T + O + A - C calculation and normalization")
    void shouldCalculateConfidenceFormulaCorrectly() {
        // T = 30, O = 30, A = 25, C = 0 -> S = 85 -> normalized = 1.00
        ConfidenceBreakdown full = ConfidenceBreakdown.calculate(
                30.0,
                30.0,
                25.0,
                0.0,
                List.of("Temporal: +30", "Overlap: +30", "Agreement: +25"),
                Collections.emptyList()
        );
        assertThat(full.rawScore()).isEqualTo(85.0);
        assertThat(full.normalizedScore()).isEqualTo(1.00);

        // T = 30, O = 0, A = 0, C = 30 -> S = 0 -> normalized = 0.00
        ConfidenceBreakdown demoted = ConfidenceBreakdown.calculate(
                30.0,
                0.0,
                0.0,
                30.0,
                List.of("Temporal: +30"),
                List.of("Subsystem contradiction: -30")
        );
        assertThat(demoted.rawScore()).isEqualTo(0.0);
        assertThat(demoted.normalizedScore()).isEqualTo(0.00);
    }

    @Test
    @DisplayName("Temporal proximity alone must never establish causation")
    void shouldNotEstablishCausationOnTemporalProximityAlone() {
        Instant tCommit = Instant.parse("2026-10-10T14:14:00Z"); // 2.5 min before failure
        ChangedFile unrelatedCode = new ChangedFile("UnrelatedReportGenerator.java", "MODIFY", 10, 2, "patch", false, Collections.emptyMap());
        CommitEvidence commit = new CommitEvidence(
                "u9911aa",
                "u9911aa",
                "Dave",
                "dave@example.com",
                tCommit,
                "Add unrelated quarterly report layout",
                List.of(unrelatedCode),
                10,
                2,
                false,
                true
        );

        StackTraceFrame frame = StackTraceFrame.of("com.traceback.payment.service.PaymentFeeCalculator", "calc", "PaymentFeeCalculator.java", 58);
        ParsedException exception = new ParsedException("PaymentCalculationException", "Calculation failed", List.of(frame), null);

        CorrelationResult result = engine.correlate(incident, List.of(commit), null, exception, registry, 60);

        // Candidate must be capped and rejected due to zero overlap with PaymentFeeCalculator
        Hypothesis hyp = result.rankedHypotheses().stream()
                .filter(h -> h.getSummary().contains("u9911aa"))
                .findFirst()
                .orElseThrow();

        assertThat(hyp.getConfidenceScore()).isLessThanOrEqualTo(0.15);
        assertThat(hyp.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
        assertThat(hyp.getCounterEvidence()).contains("Temporal proximity detected, but lacking code or configuration overlap");
    }

    @Test
    @DisplayName("Cosmetic commit must be demoted with penalty applied")
    void shouldDemoteCosmeticCommit() {
        Instant tCommit = Instant.parse("2026-10-10T14:15:00Z");
        ChangedFile readme = new ChangedFile("README.md", "MODIFY", 5, 0, "+docs", false, Collections.emptyMap());
        CommitEvidence commit = new CommitEvidence(
                "c05m371",
                "c05m371",
                "Eve",
                "eve@example.com",
                tCommit,
                "Update documentation markdown",
                List.of(readme),
                5,
                0,
                false,
                false
        );

        StackTraceFrame frame = StackTraceFrame.of("com.traceback.payment.service.PaymentFeeCalculator", "calc", "PaymentFeeCalculator.java", 58);
        ParsedException exception = new ParsedException("PaymentCalculationException", "Calculation failed", List.of(frame), null);

        CorrelationResult result = engine.correlate(incident, List.of(commit), null, exception, registry, 60);

        Hypothesis hyp = result.rankedHypotheses().stream()
                .filter(h -> h.getSummary().contains("c05m371"))
                .findFirst()
                .orElseThrow();

        assertThat(hyp.getConfidenceScore()).isLessThanOrEqualTo(0.10);
        assertThat(hyp.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
        assertThat(hyp.getPenaltyReasons()).anyMatch(p -> p.toLowerCase().contains("cosmetic"));
    }

    @Test
    @DisplayName("Superseded configuration key should be penalized when later commit modified it")
    void shouldPenalizeSupersededConfigurationCommit() {
        Instant t1 = Instant.parse("2026-10-10T13:45:00Z");
        Instant t2 = Instant.parse("2026-10-10T14:10:00Z");

        ChangedFile cf1 = new ChangedFile(
                "application.properties",
                "MODIFY",
                1,
                1,
                "patch",
                true,
                Map.of("spring.datasource.hikari.connection-timeout", "30000")
        );
        CommitEvidence commit1 = new CommitEvidence(
                "sha-initial-30s",
                "sha-ini",
                "DevOps",
                "devops@example.com",
                t1,
                "Initial configuration with 30s connection timeout",
                List.of(cf1),
                1,
                1,
                true,
                false
        );

        ChangedFile cf2 = new ChangedFile(
                "application.properties",
                "MODIFY",
                1,
                1,
                "patch",
                true,
                Map.of("spring.datasource.hikari.connection-timeout", "3000")
        );
        CommitEvidence commit2 = new CommitEvidence(
                "sha-tune-3s",
                "sha-tun",
                "Alice",
                "alice@example.com",
                t2,
                "Tune database connection timeout to 3s",
                List.of(cf2),
                1,
                1,
                true,
                false
        );

        ParsedException dbTimeout = new ParsedException("java.sql.SQLTimeoutException", "Connection timeout after 3000ms", Collections.emptyList(), null);

        // Input commits: newest first (commit2, commit1)
        CorrelationResult result = engine.correlate(incident, List.of(commit2, commit1), null, dbTimeout, registry, 60);

        Hypothesis hypCommit2 = result.rankedHypotheses().stream()
                .filter(h -> h.getSummary().contains("sha-tun"))
                .findFirst().orElseThrow();

        Hypothesis hypCommit1 = result.rankedHypotheses().stream()
                .filter(h -> h.getSummary().contains("sha-ini"))
                .findFirst().orElseThrow();

        // Commit 2 must rank above Commit 1
        assertThat(hypCommit2.getRank()).isLessThan(hypCommit1.getRank());
        assertThat(hypCommit2.getConfidenceScore()).isGreaterThan(0.70);
        // Commit 1 must have superseded penalty applied
        assertThat(hypCommit1.getPenaltyReasons()).anyMatch(p -> p.contains("superseded by a later pre-failure commit"));
    }
}
