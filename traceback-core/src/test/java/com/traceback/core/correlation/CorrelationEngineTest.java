package com.traceback.core.correlation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.log.ErrorSignature;
import com.traceback.core.log.LogEntry;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationEngineTest {

    private final CorrelationEngine engine = new CorrelationEngine();
    private final EvidenceRegistry registry = new ConcreteEvidenceRegistry();

    @Test
    @DisplayName("Should correlate code diff with stack trace frame and rank candidate #1")
    void shouldCorrelateCodeDiffWithStackTrace() {
        Instant tCommit = Instant.parse("2026-10-10T14:10:00Z");
        Instant tError = Instant.parse("2026-10-10T14:16:30Z");

        ChangedFile codeFile = new ChangedFile("PaymentFeeCalculator.java", "MODIFY", 5, 2, "patch", false, Collections.emptyMap());
        CommitEvidence commit = new CommitEvidence("c1a89b3", "c1a89b3", "Alice", "alice@example.com", tCommit, "Refactor fee math", List.of(codeFile), 5, 2, false, true);

        StackTraceFrame frame = StackTraceFrame.of("com.traceback.payment.service.PaymentFeeCalculator", "calc", "PaymentFeeCalculator.java", 58);
        ParsedException exception = new ParsedException("com.traceback.payment.exception.PaymentCalculationException", "Invalid fee", List.of(frame), null);

        Incident incident = new Incident(UUID.randomUUID(), "INC-001", "Test", "payment-service", "prod", "HIGH", "OPEN", tError, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(commit), null, exception, registry, 60);

        assertThat(result.rankedHypotheses()).hasSize(1);
        Hypothesis top = result.rankedHypotheses().get(0);
        assertThat(top.getRank()).isEqualTo(1);
        assertThat(top.getConfidenceScore()).isGreaterThanOrEqualTo(0.80);
        assertThat(top.getSummary()).contains("Code regression in commit c1a89b3");

        // Verify evidence was registered with unique ID
        assertThat(registry.findAllItems()).anyMatch(e -> e.evidenceId().contains("c1a89b3"));
    }

    @Test
    @DisplayName("Should demote cosmetic commit despite being closer in time")
    void shouldDemoteCosmeticCommit() {
        Instant tCode = Instant.parse("2026-10-10T14:10:00Z");
        Instant tCosmetic = Instant.parse("2026-10-10T14:14:00Z"); // Closer to failure!
        Instant tError = Instant.parse("2026-10-10T14:16:30Z");

        ChangedFile codeFile = new ChangedFile("PaymentFeeCalculator.java", "MODIFY", 5, 2, "patch", false, Collections.emptyMap());
        CommitEvidence codeCommit = new CommitEvidence("c1a89b3", "c1a89b3", "Alice", "alice@example.com", tCode, "Refactor fee math", List.of(codeFile), 5, 2, false, true);

        ChangedFile docsFile = new ChangedFile("README.md", "MODIFY", 1, 0, "+docs", false, Collections.emptyMap());
        CommitEvidence docsCommit = new CommitEvidence("e55b112", "e55b112", "Bob", "bob@example.com", tCosmetic, "Update README docs", List.of(docsFile), 1, 0, false, false);

        StackTraceFrame frame = StackTraceFrame.of("com.traceback.payment.service.PaymentFeeCalculator", "calc", "PaymentFeeCalculator.java", 58);
        ParsedException exception = new ParsedException("PaymentCalculationException", "Calculation failed", List.of(frame), null);

        Incident incident = new Incident(UUID.randomUUID(), "INC-001", "Test", "payment-service", "prod", "HIGH", "OPEN", tError, null, null, null);

        CorrelationResult result = engine.correlate(incident, List.of(docsCommit, codeCommit), null, exception, registry, 60);

        assertThat(result.rankedHypotheses()).hasSize(2);
        // Code commit should be Rank #1, beating the more recent docs commit
        Hypothesis rank1 = result.rankedHypotheses().get(0);
        assertThat(rank1.getSummary()).contains("c1a89b3");
        assertThat(rank1.getConfidenceScore()).isGreaterThan(0.70);

        Hypothesis rank2 = result.rankedHypotheses().get(1);
        assertThat(rank2.getSummary()).contains("e55b112");
        assertThat(rank2.getConfidenceScore()).isLessThan(0.25);
        assertThat(rank2.getCounterEvidence()).contains("cosmetic or documentation");
    }
}
