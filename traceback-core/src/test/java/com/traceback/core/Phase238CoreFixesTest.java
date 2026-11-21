package com.traceback.core;

import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostRequest;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.bifrost.DefaultBifrostClient;
import com.traceback.core.agent.tavily.DefaultTavilyResearchService;
import com.traceback.core.agent.tavily.TavilyConfig;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCollector;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.validation.DeterministicClaimValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Phase238CoreFixesTest {

    private CorrelationEngine correlationEngine;
    private LogAnalyzer logAnalyzer;
    private EvidenceRegistry registry;

    @BeforeEach
    void setUp() {
        correlationEngine = new CorrelationEngine();
        logAnalyzer = new LogAnalyzer();
        registry = new ConcreteEvidenceRegistry();
    }

    private ParsedException createNpeException() {
        StackTraceFrame frame = StackTraceFrame.of("com.traceback.PaymentService", "charge", "PaymentService.java", 55);
        return new ParsedException("java.lang.NullPointerException", "Cannot invoke method on null object", List.of(frame), null);
    }

    private ChangedFile createMatchingFile() {
        return new ChangedFile("PaymentService.java", "MODIFY", 8, 1, "patch", false, Collections.emptyMap());
    }

    @Test
    @DisplayName("CORR-01: Missing timestamps award 0 temporal points and result in PROBABLE verdict, never CONFIRMED")
    void testMissingTimestampsDoNotReceivePositiveScoreAndYieldProbable() {
        CommitEvidence commit = new CommitEvidence(
                "c999aaa", "c999aaa", "Dev", "dev@example.com",
                null, "Update PaymentService logic", List.of(createMatchingFile()), 8, 1, false, true
        );

        Incident incident = new Incident(UUID.randomUUID(), "INC-P238", "Test", "payment-service", "prod", "HIGH", "OPEN", null, null, null, null);

        CorrelationResult result = correlationEngine.correlate(incident, List.of(commit), null, createNpeException(), registry, 60);

        assertThat(result.evidenceSufficient()).isTrue();
        Hypothesis top = result.rankedHypotheses().get(0);
        assertThat(top.getStatus()).isEqualTo(HypothesisStatus.INVESTIGATING);
        // O=30, T=0 -> Score = 30/85 = 0.353
        assertThat(top.getConfidenceScore()).isCloseTo(0.353, org.assertj.core.data.Offset.offset(0.01));

        ConfidenceBreakdown breakdown = result.confidenceBreakdownsByCommitSha().get("c999aaa");
        assertThat(breakdown.temporalPoints()).isEqualTo(0.0);
        assertThat(breakdown.scoreReasons()).anyMatch(r -> r.contains("Temporal information unavailable"));

        // Verify InvestigationAgent maps this to PROBABLE (not CONFIRMED)
        BifrostClient mockBifrost = mock(BifrostClient.class);
        when(mockBifrost.isAvailable()).thenReturn(false);
        InvestigationService mockInvService = mock(InvestigationService.class);

        InvestigationAgent agent = new InvestigationAgent(
                mockBifrost,
                new DefaultTavilyResearchService(TavilyConfig.fromEnvironment()),
                new DeterministicClaimValidator(),
                registry,
                mockInvService
        );

        InvestigationResult invResult = new InvestigationResult(
                incident.getId(),
                "payment-service",
                "prod",
                Instant.now(),
                Instant.now(),
                "COMPLETED",
                1, 1,
                createNpeException(),
                Collections.emptyList(),
                registry.findByInvestigationId(incident.getId()),
                Collections.emptyList(),
                result.rankedHypotheses(),
                result.supportingEvidenceByCommitSha(),
                result.counterEvidenceByCommitSha(),
                result.confidenceBreakdownsByCommitSha(),
                result.claims(),
                result.evidenceSufficient()
        );

        InvestigationAgentReport report = agent.investigate(invResult);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
    }

    @Test
    @DisplayName("LOG-01: Invalid log timestamps return null instead of silently defaulting to Instant.now()")
    void testInvalidLogTimestampsDoNotDefaultToInstantNow() {
        // Test JSON log entry with unparseable timestamp
        String jsonLogLine = "{\"timestamp\": \"INVALID-DATE-FORMAT\", \"level\": \"ERROR\", \"message\": \"Crash observed\"}";
        LogEvidence evidence = logAnalyzer.analyzeContent(jsonLogLine);

        assertThat(evidence.totalErrors()).isEqualTo(1);
        assertThat(evidence.firstObservedFailure()).isNull();

        // Test standard line with invalid out-of-range date
        String regexLogLine = "2026-99-99 99:99:99.999 ERROR [main] com.traceback.Service - Crash observed";
        LogEvidence evidenceRegex = logAnalyzer.analyzeContent(regexLogLine);
        assertThat(evidenceRegex.totalErrors()).isEqualTo(1);
        assertThat(evidenceRegex.firstObservedFailure()).isNull();
    }

    @Test
    @DisplayName("SEC-03: InvestigationService rejects logFilePath outside authorized repository roots")
    void testLogFilePathOutsideAuthorizedRootsThrowsSecurityException(@TempDir Path tempDir) throws IOException {
        Path outsideLog = tempDir.resolve("unauthorized_system.log");
        Files.writeString(outsideLog, "2026-11-15 10:00:00.000 ERROR [main] logger: failure");

        // GitCollector authorized only for project dir, not tempDir
        GitCollector restrictedCollector = new GitCollector(List.of(Path.of(".").toAbsolutePath().normalize().toString()));
        InvestigationService service = new InvestigationService(
                restrictedCollector,
                new StackTraceParser(),
                new LogAnalyzer(),
                new TimelineBuilder(),
                new CorrelationEngine(),
                new ConcreteEvidenceRegistry()
        );

        assertThatThrownBy(() -> service.startInvestigation(
                Path.of(".").toString(),
                null,
                outsideLog.toAbsolutePath().toString(),
                null,
                "test-service",
                "prod",
                60, 50
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("Log file path is not within the authorized repository roots");
    }

    @Test
    @DisplayName("SEC-03: LogAnalyzer rejects log files exceeding 5MB ceiling")
    void testLogFileExceeding5MBThrowsIllegalArgumentException(@TempDir Path tempDir) throws IOException {
        Path oversizedLog = tempDir.resolve("huge.log");
        byte[] dummyData = new byte[5 * 1024 * 1024 + 1024]; // 5MB + 1KB
        Files.write(oversizedLog, dummyData);

        assertThatThrownBy(() -> logAnalyzer.analyzeFile(oversizedLog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Log file exceeds maximum allowed size of 5MB");
    }

    @Test
    @DisplayName("Bifrost Integration: DefaultBifrostClient isAvailable is false when API key is missing")
    void testBifrostIsAvailableFalseWhenUnconfigured() {
        BifrostConfig emptyKeyConfig = new BifrostConfig("http://localhost:8080/v1", null, "gpt-4o", Duration.ofSeconds(5), 1);
        DefaultBifrostClient client = new DefaultBifrostClient(emptyKeyConfig);

        assertThat(client.isAvailable()).isFalse();
        assertThat(client.getConfig().maskedApiKey()).isEqualTo("[NOT_SET]");
    }
}
