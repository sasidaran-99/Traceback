package com.traceback.core.correlation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.log.ErrorSignature;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Phase236TemporalScoringTest {

    private CorrelationEngine engine;
    private EvidenceRegistry registry;

    @BeforeEach
    void setUp() {
        engine = new CorrelationEngine();
        registry = new ConcreteEvidenceRegistry();
    }

    private ParsedException createTestException() {
        StackTraceFrame frame = StackTraceFrame.of("com.traceback.service.OrderProcessor", "process", "OrderProcessor.java", 42);
        return new ParsedException("java.lang.NullPointerException", "Cannot invoke null object", List.of(frame), null);
    }

    private ChangedFile createMatchingCodeFile() {
        return new ChangedFile("OrderProcessor.java", "MODIFY", 10, 2, "patch", false, Collections.emptyMap());
    }

    private ChangedFile createUnrelatedCodeFile() {
        return new ChangedFile("UnrelatedReport.java", "MODIFY", 5, 1, "patch", false, Collections.emptyMap());
    }

    @Test
    @DisplayName("Case 1: Within window positive correlation (<= 15m gets +30, > 15m gets +20)")
    void testWithinWindowPositiveCorrelation() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");
        Instant commit5m = failureTime.minus(Duration.ofMinutes(5));
        Instant commit30m = failureTime.minus(Duration.ofMinutes(30));

        CommitEvidence c1 = new CommitEvidence("c111111", "c111111", "Dev", "dev@example.com",
                commit5m, "Fix order processing", List.of(createMatchingCodeFile()), 10, 2, false, true);
        CommitEvidence c2 = new CommitEvidence("c222222", "c222222", "Dev", "dev@example.com",
                commit30m, "Refactor order processing", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-1", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result1 = engine.correlate(incident, List.of(c1), null, createTestException(), registry, 60);
        ConfidenceBreakdown b1 = result1.confidenceBreakdownsByCommitSha().get("c111111");
        assertThat(b1.temporalPoints()).isEqualTo(30.0);
        assertThat(b1.scoreReasons()).anyMatch(r -> r.contains("close proximity bonus: +10 pts"));

        CorrelationResult result2 = engine.correlate(incident, List.of(c2), null, createTestException(), registry, 60);
        ConfidenceBreakdown b2 = result2.confidenceBreakdownsByCommitSha().get("c222222");
        assertThat(b2.temporalPoints()).isEqualTo(20.0);
        assertThat(b2.scoreReasons()).anyMatch(r -> r.contains("within 60m window"));
    }

    @Test
    @DisplayName("Case 2: Extended window (between 1x and 2x window, e.g. 60-120m, gets +10)")
    void testExtendedWindowScoring() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");
        Instant commit90m = failureTime.minus(Duration.ofMinutes(90)); // 90m > 60m window, but <= 120m

        CommitEvidence c = new CommitEvidence("c333333", "c333333", "Dev", "dev@example.com",
                commit90m, "Order change", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-2", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        ConfidenceBreakdown b = result.confidenceBreakdownsByCommitSha().get("c333333");
        assertThat(b.temporalPoints()).isEqualTo(10.0);
        assertThat(b.scoreReasons()).anyMatch(r -> r.contains("extended window 60-120m"));
    }

    @Test
    @DisplayName("Case 3: Outside window (> 2x window gets 0 temporal pts without penalty)")
    void testOutsideWindowScoring() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");
        Instant commit300m = failureTime.minus(Duration.ofMinutes(300)); // 5 hours prior

        CommitEvidence c = new CommitEvidence("c444444", "c444444", "Dev", "dev@example.com",
                commit300m, "Order change", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-3", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        ConfidenceBreakdown b = result.confidenceBreakdownsByCommitSha().get("c444444");
        assertThat(b.temporalPoints()).isEqualTo(0.0);
        assertThat(b.contradictionPenalty()).isEqualTo(0.0);
        assertThat(b.scoreReasons()).anyMatch(r -> r.contains("exceeds 2x window of 120m"));
    }

    @Test
    @DisplayName("Case 4: Missing log timestamp (failureOnset is null) gets neutral +0 pts baseline")
    void testMissingLogTimestampNeutralBaseline() {
        Instant commitTime = Instant.parse("2026-11-15T10:00:00Z");

        CommitEvidence c = new CommitEvidence("c555555", "c555555", "Dev", "dev@example.com",
                commitTime, "Order change", List.of(createMatchingCodeFile()), 10, 2, false, true);

        // Incident with null startedAt and null log evidence
        Incident incident = new Incident(UUID.randomUUID(), "INC-4", "Failure", "order-service", "prod", "HIGH", "OPEN", null, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        ConfidenceBreakdown b = result.confidenceBreakdownsByCommitSha().get("c555555");
        assertThat(b.temporalPoints()).isEqualTo(0.0);
        assertThat(b.scoreReasons()).anyMatch(r -> r.contains("Temporal information unavailable"));
    }

    @Test
    @DisplayName("Case 5: Missing commit timestamp (commit.timestamp() is null) gets neutral +0 pts baseline")
    void testMissingCommitTimestampNeutralBaseline() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");

        CommitEvidence c = new CommitEvidence("c666666", "c666666", "Dev", "dev@example.com",
                null, "Order change without timestamp", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-5", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        ConfidenceBreakdown b = result.confidenceBreakdownsByCommitSha().get("c666666");
        assertThat(b.temporalPoints()).isEqualTo(0.0);
        assertThat(b.scoreReasons()).anyMatch(r -> r.contains("Temporal information unavailable"));
    }

    @Test
    @DisplayName("Case 6: Timezone offsets evaluate accurately via Instant duration")
    void testTimezoneOffsetEvaluation() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");
        // Commit timestamp equivalent to 10:10:00 UTC represented with +05:30 offset
        Instant commitTime = Instant.parse("2026-11-15T15:40:00+05:30"); // 10:10:00Z (10 minutes before failure)

        CommitEvidence c = new CommitEvidence("c777777", "c777777", "Dev", "dev@example.com",
                commitTime, "Order change with tz", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-6", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        ConfidenceBreakdown b = result.confidenceBreakdownsByCommitSha().get("c777777");
        // 10 minutes <= 15 minutes, receives full +30 points
        assertThat(b.temporalPoints()).isEqualTo(30.0);
        assertThat(b.scoreReasons()).anyMatch(r -> r.contains("close proximity bonus: +10 pts"));
    }

    @Test
    @DisplayName("Case 7: Unsynchronized synthetic timestamps (O=30, T=0 -> S >= 0.35 -> INVESTIGATING and sufficient)")
    void testUnsynchronizedSyntheticTimestampsPreservesInvestigatingHypothesis() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");
        Instant commit346m = failureTime.minus(Duration.ofMinutes(346)); // exactly the Phase 2.35.1 discrepancy

        // Test with pure code overlap (no corroborating commit message keywords)
        CommitEvidence cPure = new CommitEvidence("768f5a8", "768f5a8", "Dev", "dev@example.com",
                commit346m, "Update OrderProcessor logic", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-7", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult resultPure = engine.correlate(incident, List.of(cPure), null, createTestException(), registry, 60);
        assertThat(resultPure.evidenceSufficient()).isTrue();
        assertThat(resultPure.rankedHypotheses()).isNotEmpty();

        Hypothesis topPure = resultPure.rankedHypotheses().get(0);
        assertThat(topPure.getRank()).isEqualTo(1);
        assertThat(topPure.getStatus()).isEqualTo(HypothesisStatus.INVESTIGATING);
        // O=30, T=0, A=0 -> 30/85 = 0.353
        assertThat(topPure.getConfidenceScore()).isCloseTo(0.353, org.assertj.core.data.Offset.offset(0.01));
        assertThat(topPure.getSummary()).contains("Code regression in commit 768f5a8");

        // Test with message corroboration (keyword 'calc' adds +10 agreement pts -> 40/85 = 0.471)
        CommitEvidence cCorroborated = new CommitEvidence("768f5a8", "768f5a8", "Dev", "dev@example.com",
                commit346m, "Break calculateTotal", List.of(createMatchingCodeFile()), 10, 2, false, true);

        CorrelationResult resultCorroborated = engine.correlate(incident, List.of(cCorroborated), null, createTestException(), registry, 60);
        Hypothesis topCorroborated = resultCorroborated.rankedHypotheses().get(0);
        assertThat(topCorroborated.getStatus()).isEqualTo(HypothesisStatus.INVESTIGATING);
        assertThat(topCorroborated.getConfidenceScore()).isCloseTo(0.471, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Case 8: Strong code overlap with no temporal info (O=30, T=0 -> S=0.353, INVESTIGATING)")
    void testStrongCodeOverlapWithoutTemporalInfo() {
        CommitEvidence c = new CommitEvidence("c888888", "c888888", "Dev", "dev@example.com",
                null, "Change OrderProcessor", List.of(createMatchingCodeFile()), 10, 2, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-8", "Failure", "order-service", "prod", "HIGH", "OPEN", null, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        assertThat(result.evidenceSufficient()).isTrue();
        Hypothesis top = result.rankedHypotheses().get(0);
        assertThat(top.getStatus()).isEqualTo(HypothesisStatus.INVESTIGATING);
        assertThat(top.getConfidenceScore()).isCloseTo(0.353, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Case 9: Weak code overlap with strong temporal (O=0, T=30 -> capped at 0.15 with causation penalty)")
    void testTemporalProximityAloneCannotEstablishCausation() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");
        Instant commit5m = failureTime.minus(Duration.ofMinutes(5));

        CommitEvidence c = new CommitEvidence("c999999", "c999999", "Dev", "dev@example.com",
                commit5m, "Unrelated file touch", List.of(createUnrelatedCodeFile()), 5, 1, false, true);

        Incident incident = new Incident(UUID.randomUUID(), "INC-9", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c), null, createTestException(), registry, 60);
        ConfidenceBreakdown b = result.confidenceBreakdownsByCommitSha().get("c999999");
        assertThat(b.overlapPoints()).isEqualTo(0.0);
        assertThat(b.temporalPoints()).isEqualTo(30.0);
        assertThat(b.penaltyReasons()).anyMatch(p -> p.contains("Temporal proximity alone cannot establish causation"));

        Hypothesis top = result.rankedHypotheses().get(0);
        assertThat(top.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
        assertThat(top.getConfidenceScore()).isLessThanOrEqualTo(0.15);
    }

    @Test
    @DisplayName("Case 10: Multiple candidate commits with competing scores ranked descending by confidence then timestamp")
    void testMultipleCandidateCommitsRankedProperly() {
        Instant failureTime = Instant.parse("2026-11-15T10:20:00Z");

        // Commit 1: Direct overlap, within 10m -> T=30, O=30 -> Score ~0.71
        CommitEvidence c1 = new CommitEvidence("c111", "c111", "Alice", "alice@example.com",
                failureTime.minus(Duration.ofMinutes(10)), "Buggy code change", List.of(createMatchingCodeFile()), 5, 1, false, true);

        // Commit 2: Direct overlap, 90m prior -> T=10, O=30 -> Score ~0.47
        CommitEvidence c2 = new CommitEvidence("c222", "c222", "Bob", "bob@example.com",
                failureTime.minus(Duration.ofMinutes(90)), "Older code change", List.of(createMatchingCodeFile()), 5, 1, false, true);

        // Commit 3: Unrelated file, 5m prior -> T=30, O=0 -> capped at 0.15
        CommitEvidence c3 = new CommitEvidence("c333", "c333", "Charlie", "charlie@example.com",
                failureTime.minus(Duration.ofMinutes(5)), "Unrelated doc touch", List.of(createUnrelatedCodeFile()), 2, 0, false, false);

        Incident incident = new Incident(UUID.randomUUID(), "INC-10", "Failure", "order-service", "prod", "HIGH", "OPEN", failureTime, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(c3, c2, c1), null, createTestException(), registry, 60);
        assertThat(result.rankedHypotheses()).hasSize(3);

        assertThat(result.rankedHypotheses().get(0).getSummary()).contains("c111");
        assertThat(result.rankedHypotheses().get(0).getRank()).isEqualTo(1);
        assertThat(result.rankedHypotheses().get(0).getConfidenceScore()).isGreaterThan(0.70);

        assertThat(result.rankedHypotheses().get(1).getSummary()).contains("c222");
        assertThat(result.rankedHypotheses().get(1).getRank()).isEqualTo(2);
        assertThat(result.rankedHypotheses().get(1).getConfidenceScore()).isCloseTo(0.471, org.assertj.core.data.Offset.offset(0.02));

        assertThat(result.rankedHypotheses().get(2).getSummary()).contains("c333");
        assertThat(result.rankedHypotheses().get(2).getRank()).isEqualTo(3);
        assertThat(result.rankedHypotheses().get(2).getConfidenceScore()).isLessThanOrEqualTo(0.15);
    }
}
