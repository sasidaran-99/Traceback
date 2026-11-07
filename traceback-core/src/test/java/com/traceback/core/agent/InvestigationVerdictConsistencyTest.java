package com.traceback.core.agent;

import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostRequest;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCommitAnalyzer;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test suite verifying investigation verdict consistency, causal qualification,
 * truthful agent fallback reasoning, and exception chain preservation.
 */
class InvestigationVerdictConsistencyTest {

    private BifrostClient mockBifrostClient;
    private TavilyResearchService mockTavilyService;
    private DeterministicClaimValidator claimValidator;
    private EvidenceRegistry evidenceRegistry;
    private InvestigationService mockInvestigationService;

    private InvestigationAgent agent;
    private CorrelationEngine correlationEngine;

    @BeforeEach
    void setUp() {
        mockBifrostClient = mock(BifrostClient.class);
        mockTavilyService = mock(TavilyResearchService.class);
        claimValidator = new DeterministicClaimValidator();
        evidenceRegistry = new ConcreteEvidenceRegistry();
        mockInvestigationService = mock(InvestigationService.class);
        correlationEngine = new CorrelationEngine(new GitCommitAnalyzer(), claimValidator);

        BifrostConfig bConfig = new BifrostConfig("http://localhost:8080/v1", "sk-bf-mock", "gpt-4o", Duration.ofSeconds(5), 1);
        when(mockBifrostClient.getConfig()).thenReturn(bConfig);

        agent = new InvestigationAgent(
                mockBifrostClient,
                mockTavilyService,
                claimValidator,
                evidenceRegistry,
                mockInvestigationService
        );
    }

    private InvestigationResult buildResult(
            UUID invId,
            String status,
            int commitsAnalyzed,
            int errorsDetected,
            ParsedException pe,
            List<Hypothesis> hypotheses,
            Map<String, ConfidenceBreakdown> breakdowns,
            List<InvestigationClaim> claims,
            boolean evidenceSufficient
    ) {
        return new InvestigationResult(
                invId, "payment-service", "production",
                Instant.now().minusSeconds(10), Instant.now(), status,
                commitsAnalyzed, errorsDetected, pe,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                hypotheses,
                hypotheses.isEmpty() ? Collections.emptyMap() : Map.of("738c46c", List.of("EVD-GIT-738c46c")),
                Collections.emptyMap(),
                breakdowns != null ? breakdowns : Collections.emptyMap(),
                claims != null ? claims : Collections.emptyList(),
                evidenceSufficient
        );
    }

    @Test
    @DisplayName("1. Inconclusive deterministic analysis cannot produce a CONFIRMED agent verdict")
    void test1_inconclusiveAnalysisCannotProduceConfirmedVerdict() {
        UUID invId = UUID.randomUUID();
        Incident incident = new Incident();
        incident.setId(invId);

        // Low confidence hypothesis (e.g. 0.20) with insufficient evidence
        Hypothesis lowConfidenceHypo = new Hypothesis(
                UUID.randomUUID(), incident, null, 1, 0.20,
                HypothesisStatus.REJECTED, "Weak correlation commit", "Inspect diff", "Lacks overlap"
        );

        InvestigationResult inconclusiveResult = buildResult(
                invId, "INSUFFICIENT_EVIDENCE", 2, 0, null,
                List.of(lowConfidenceHypo), Collections.emptyMap(), Collections.emptyList(), false
        );

        InvestigationAgentReport report = agent.investigate(inconclusiveResult);

        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(report.finalStatus()).isNotEqualTo(AgentInvestigationStatus.CONFIRMED);
        assertThat(report.summary()).contains("inconclusive due to insufficient evidence");
    }

    @Test
    @DisplayName("2. Unsupported causal claim cannot be presented as confirmed")
    void test2_unsupportedCausalClaimCannotBePresentedAsConfirmed() {
        UUID invId = UUID.randomUUID();

        // Register supporting evidence and contradiction evidence
        evidenceRegistry.registerItem(EvidenceItem.create(
                "EVD-GIT-aff147e",
                EvidenceType.COMMIT,
                "GIT",
                "aff147e",
                Instant.now(),
                "Update authentication error message",
                Map.of("type", "COMMIT")
        ));

        evidenceRegistry.registerItem(EvidenceItem.create(
                "EVD-CONTRA-SUBSYS-aff147e",
                EvidenceType.COMMIT,
                "ANALYSIS",
                "aff147e",
                Instant.now(),
                "Subsystem contradiction: touched AUTHENTICATION with zero database or stack trace overlap",
                Map.of("type", "SUBSYSTEM_CONTRADICTION")
        ));

        InvestigationClaim contradictedClaim = InvestigationClaim.create(
                "CLM-aff147e",
                invId,
                "Candidate commit aff147e caused database timeout failure",
                List.of("EVD-GIT-aff147e"),
                List.of("EVD-CONTRA-SUBSYS-aff147e")
        );

        InvestigationClaim validated = claimValidator.validateClaim(contradictedClaim, invId, evidenceRegistry);
        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.CONTRADICTED);

        // Verify that an investigation with an unrefuted penalty cannot be CONFIRMED
        ConfidenceBreakdown penaltyBreakdown = ConfidenceBreakdown.calculate(
                30.0, 0.0, 0.0, 30.0,
                List.of("Temporal proximity: +30"),
                List.of("Subsystem contradiction: -30")
        );

        Hypothesis penalizedHypo = new Hypothesis(
                UUID.randomUUID(), null, null, 1, 0.0,
                HypothesisStatus.REJECTED, "Commit aff147e shows weak correlation",
                "None", "Subsystem contradiction", List.of(), List.of("EVD-CONTRA-SUBSYS-aff147e"),
                List.of(), Map.of(), List.of("Subsystem contradiction"), "Ranked #2", penaltyBreakdown
        );

        InvestigationResult penalizedResult = buildResult(
                invId, "INSUFFICIENT_EVIDENCE", 2, 0, null,
                List.of(penalizedHypo), Map.of("aff147e", penaltyBreakdown), List.of(validated), false
        );

        InvestigationAgentReport report = agent.investigate(penalizedResult);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(report.finalStatus()).isNotEqualTo(AgentInvestigationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("3. Summary and verdict badge cannot disagree")
    void test3_summaryAndVerdictBadgeCannotDisagree() {
        UUID invId = UUID.randomUUID();
        Incident incident = new Incident();
        incident.setId(invId);

        Event commitEvent = new Event(UUID.randomUUID(), EventType.CONFIG_CHANGE, EventSource.GITHUB,
                "payment-service", "production", Instant.now().minusSeconds(300), "738c46c", "{}");

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                30.0, 30.0, 0.0, 0.0,
                List.of("Temporal: +30", "Config overlap: +30"),
                Collections.emptyList()
        );

        Hypothesis probableHypo = new Hypothesis(
                UUID.randomUUID(), incident, commitEvent, 1, 0.71,
                HypothesisStatus.PROPOSED,
                "Potential contributing change in commit 738c46c: modified configuration settings (spring.datasource.hikari.maximum-pool-size)",
                "Review git diff", "None",
                List.of("EVD-GIT-738c46c"), Collections.emptyList(),
                List.of("RULE_A_TEMPORAL_PROXIMITY", "RULE_C_CONFIG_OVERLAP"),
                Map.of(), Collections.emptyList(), "Ranked #1", breakdown
        );

        ParsedException pe = new ParsedException("org.springframework.dao.CannotAcquireLockException", "Lock acquisition failure",
                List.of(), new ParsedException("java.sql.SQLTransientConnectionException", "HikariPool-1 timeout", List.of(), null));

        InvestigationResult result = buildResult(
                invId, "PROBABLE", 2, 1, pe,
                List.of(probableHypo), Map.of("738c46c", breakdown), Collections.emptyList(), true
        );

        InvestigationAgentReport report = agent.investigate(result);

        // When verdict badge is PROBABLE, summary must NOT say inconclusive and must NOT say confirmed
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
        assertThat(report.summary()).doesNotContain("inconclusive due to insufficient evidence");
        assertThat(report.summary()).doesNotContain("confirmed commit");
        assertThat(report.summary()).contains("probable contributing change");
    }

    @Test
    @DisplayName("4. Zero observed error logs cannot be displayed as positive log corroboration")
    void test4_zeroObservedErrorLogsCannotBeCorroboration() {
        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                30.0, 30.0, 0.0, 0.0,
                List.of("Temporal: +30 pts", "Config overlap: +30 pts"),
                Collections.emptyList()
        );

        // Rule D repeated failure points should be 0.0
        assertThat(breakdown.agreementPoints()).isEqualTo(0.0);
        assertThat(breakdown.scoreReasons()).noneMatch(r -> r.contains("Persistent runtime error burst"));

        // Fallback reasoning with 0 log errors and a stack trace should not claim positive log agreement
        InvestigationResult res = buildResult(
                UUID.randomUUID(), "PROBABLE", 2, 0,
                new ParsedException("java.sql.SQLTimeoutException", "Connection timeout", List.of(), null),
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), false
        );

        InvestigationAgentReport report = agent.investigate(res);
        assertThat(report.aiReasoningSummary()).doesNotContain("Persistent runtime error burst");
    }

    @Test
    @DisplayName("5. Plausible configuration hypothesis can be displayed without claiming deployment causality")
    void test5_plausibleConfigurationHypothesisDoesNotClaimDeploymentCausality() {
        CommitEvidence commit = new CommitEvidence(
                "738c46c3aa0d9fdf1967119159f8a372d8e48906",
                "738c46c",
                "Dev",
                "dev@example.com",
                Instant.now().minusSeconds(600),
                "Tune HikariCP maximum pool size to 2 for cost reduction",
                List.of(new ChangedFile("src/main/resources/application.properties", "MODIFY", 2, 1, "", true,
                        Map.of("spring.datasource.hikari.maximum-pool-size", "2",
                                "spring.datasource.hikari.connection-timeout", "3000"))),
                2,
                1,
                true,
                false
        );

        ParsedException crash = new ParsedException(
                "java.sql.SQLTransientConnectionException",
                "HikariPool-1 - Connection is not available, request timed out after 3000ms.",
                List.of(StackTraceFrame.of("com.zaxxer.hikari.pool.HikariPool", "getConnection", "HikariPool.java", 197)),
                null
        );

        Incident incident = new Incident(UUID.randomUUID(), "INC-DEMO", "Pool timeout", "payment-service", "production", "HIGH", "OPEN", Instant.now(), null, null, null);

        CorrelationResult result = correlationEngine.correlate(
                incident, List.of(commit), null, crash, evidenceRegistry, 60
        );

        assertThat(result.rankedHypotheses()).isNotEmpty();
        Hypothesis top = result.rankedHypotheses().get(0);

        // Must NOT claim direct causation or deployment causality
        assertThat(top.getSummary()).doesNotContain("directly triggered runtime failure");
        assertThat(top.getSummary()).contains("Potential contributing change");
        assertThat(top.getSummary()).contains("does not independently establish that the modified configuration was deployed or active during the incident");
    }

    @Test
    @DisplayName("6. Nested exceptions are represented accurately")
    void test6_nestedExceptionsRepresentedAccurately() {
        String rawStackTrace = """
                org.springframework.dao.CannotAcquireLockException: Could not acquire JDBC Connection
                	at org.springframework.jdbc.datasource.DataSourceUtils.getConnection(DataSourceUtils.java:94)
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 3000ms.
                	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                """;

        StackTraceParser parser = new StackTraceParser();
        ParsedException parsed = parser.parse(rawStackTrace).orElseThrow();

        assertThat(parsed.exceptionType()).isEqualTo("org.springframework.dao.CannotAcquireLockException");
        assertThat(parsed.getRootCause().exceptionType()).isEqualTo("java.sql.SQLTransientConnectionException");

        // Format chain explicitly shows the wrapper and root cause relationship
        String chain = parsed.formatChain();
        assertThat(chain).contains("org.springframework.dao.CannotAcquireLockException");
        assertThat(chain).contains("caused by java.sql.SQLTransientConnectionException");

        String typeChain = parsed.formatTypeChain();
        assertThat(typeChain).isEqualTo("org.springframework.dao.CannotAcquireLockException (caused by java.sql.SQLTransientConnectionException)");
    }

    @Test
    @DisplayName("7. AI-generated reasoning cannot override deterministic validation")
    void test7_aiGeneratedReasoningCannotOverrideDeterministicValidation() {
        UUID invId = UUID.randomUUID();

        // Deterministic analysis establishes PROBABLE (not CONFIRMED)
        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                30.0, 30.0, 0.0, 0.0,
                List.of("Temporal: +30", "Config overlap: +30"),
                Collections.emptyList()
        );

        Hypothesis probableHypo = new Hypothesis(
                UUID.randomUUID(), null, null, 1, 0.71,
                HypothesisStatus.PROPOSED,
                "Potential contributing change in commit 738c46c: modified pool settings",
                "Verify deployment", "None",
                List.of("EVD-GIT-738c46c"), Collections.emptyList(),
                List.of("RULE_C_CONFIG_OVERLAP"), Map.of(), Collections.emptyList(), "Ranked #1", breakdown
        );

        InvestigationResult result = buildResult(
                invId, "PROBABLE", 2, 1,
                new ParsedException("java.sql.SQLTimeoutException", "Connection timeout", List.of(), null),
                List.of(probableHypo), Map.of("738c46c", breakdown), Collections.emptyList(), true
        );

        // Mock Bifrost AI Gateway returning an aggressive override claim:
        when(mockBifrostClient.isAvailable()).thenReturn(true);
        String aggressiveAiResponse = """
                SUMMARY: CONFIRMED: Commit 738c46c directly triggered runtime failure and crashed the database!
                ROOT CAUSE: Commit 738c46c directly caused catastrophic failure.
                REASONING: Full certainty established without need for deployment verification.
                VERIFICATION STEPS:
                - None needed
                UNCERTAINTIES:
                - Zero
                """;

        when(mockBifrostClient.chatCompletion(any(BifrostRequest.class))).thenReturn(
                new BifrostResponse("mock-id", "chat.completion", 100, "gpt-4o",
                        List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(aggressiveAiResponse), "stop")),
                        new BifrostResponse.Usage(50, 50, 100))
        );

        InvestigationAgentReport report = agent.investigate(result);

        // Agent report MUST NOT allow AI to override deterministic final status to CONFIRMED
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
        assertThat(report.finalStatus()).isNotEqualTo(AgentInvestigationStatus.CONFIRMED);

        // Summary must be sanitized/grounded rather than adopting unsupported confirmed phrasing
        assertThat(report.summary()).doesNotContain("directly triggered runtime failure");
        assertThat(report.summary()).contains("probable contributing change");
    }
}
