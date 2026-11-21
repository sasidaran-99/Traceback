package com.traceback.core.agent;

import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostRequest;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.tavily.ExternalResearchFinding;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.ConfidenceBreakdown;
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
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestigationAgentTest {

    private BifrostClient mockBifrostClient;
    private TavilyResearchService mockTavilyService;
    private DeterministicClaimValidator claimValidator;
    private EvidenceRegistry evidenceRegistry;
    private InvestigationService mockInvestigationService;

    private InvestigationAgent agent;

    @BeforeEach
    void setUp() {
        mockBifrostClient = mock(BifrostClient.class);
        mockTavilyService = mock(TavilyResearchService.class);
        claimValidator = new DeterministicClaimValidator();
        evidenceRegistry = new ConcreteEvidenceRegistry();
        mockInvestigationService = mock(InvestigationService.class);

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

    @Test
    @DisplayName("Should execute end-to-end investigation with mocked Bifrost reasoning and Tavily technical research")
    void shouldExecuteEndToEndInvestigationSuccessfully() {
        UUID invId = UUID.randomUUID();

        // 1. Register verified evidence in EvidenceRegistry
        EvidenceItem gitEvidence = EvidenceItem.create(
                "EVD-GIT-c0ffee1",
                EvidenceType.COMMIT_DIFF,
                "git",
                "c0ffee1",
                Instant.now(),
                "Reduced maximum pool size from 20 to 2 in application.yml",
                Map.of("commitSha", "c0ffee1", "changedFiles", List.of("src/main/resources/application.yml"))
        );
        evidenceRegistry.registerItem(gitEvidence);

        // 2. Setup mock deterministic investigation result
        Event commitEvent = new Event(UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB,
                "order-service", "prod", Instant.now().minusSeconds(300), "c0ffee1", "{}");
        Incident incident = new Incident();
        incident.setId(invId);

        Hypothesis hypothesis = new Hypothesis(
                UUID.randomUUID(), incident, commitEvent, 1, 0.85,
                HypothesisStatus.CONFIRMED, "Commit c0ffee1 modified HikariCP pool configuration",
                "Review application.yml and revert pool size", null
        );

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                28.0, 25.0, 20.0, 0.0,
                List.of("Strong temporal proximity", "Configuration delta detected"),
                List.of()
        );

        ParsedException pe = new ParsedException(
                "com.zaxxer.hikari.pool.HikariPool$PoolInitializationException",
                "Connection is not available, request timed out after 30000ms",
                List.of(StackTraceFrame.of("com.traceback.order.OrderService", "processPayment", "OrderService.java", 45)),
                null
        );

        InvestigationResult deterministicResult = new InvestigationResult(
                invId, "order-service", "production",
                Instant.now().minusSeconds(10), Instant.now(), "COMPLETED",
                5, 12, pe, List.of(), List.of(gitEvidence), List.of(),
                List.of(hypothesis),
                Map.of("c0ffee1", List.of("EVD-GIT-c0ffee1")),
                Map.of(),
                Map.of("c0ffee1", breakdown),
                List.of(),
                true
        );

        // 3. Mock Tavily research response
        when(mockTavilyService.isAvailable()).thenReturn(true);
        ExternalResearchFinding finding = ExternalResearchFinding.create(
                "HikariPool Connection is not available",
                "HikariCP Pool Analysis Guide",
                "https://github.com/brettwooldridge/HikariCP/wiki/Pool-Analysis",
                "Pool exhaustion happens when active threads exceed maximumPoolSize.",
                0.95
        );
        when(mockTavilyService.research(anyString(), anyString())).thenReturn(List.of(finding));

        // 4. Mock Bifrost completion response
        when(mockBifrostClient.isAvailable()).thenReturn(true);
        String aiResponseText = """
                SUMMARY: Database connection pool exhaustion caused service outage.
                ROOT CAUSE: Commit c0ffee1 reduced maximumPoolSize to 2 in application.yml.
                REASONING: The pool size change in commit c0ffee1 directly precipitated the HikariPool timeout. Supported by EVD-GIT-c0ffee1.
                VERIFICATION STEPS:
                - Inspect application.yml in commit c0ffee1
                - Increase maximum-pool-size to 20
                - Re-run load test
                UNCERTAINTIES:
                - Verify background worker thread pool consumption
                """;
        BifrostResponse aiResponse = new BifrostResponse(
                "chatcmpl-test", "chat.completion", 12345, "gpt-4o",
                List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(aiResponseText), "stop")),
                new BifrostResponse.Usage(150, 60, 210)
        );
        when(mockBifrostClient.chatCompletion(any(BifrostRequest.class))).thenReturn(aiResponse);

        // Run agent investigation
        InvestigationAgentReport report = agent.investigate(deterministicResult);

        // Assertions
        assertThat(report).isNotNull();
        assertThat(report.investigationId()).isEqualTo(invId);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.CONFIRMED);
        assertThat(report.summary()).contains("Database connection pool exhaustion");
        assertThat(report.primarySuspectedRootCause()).contains("c0ffee1");
        assertThat(report.externalResearchFindings()).hasSize(1);
        assertThat(report.externalResearchFindings().get(0).findingId()).startsWith("RES-TAVILY-");
        assertThat(report.sourceUrls()).contains("https://github.com/brettwooldridge/HikariCP/wiki/Pool-Analysis");
        assertThat(report.recommendedVerificationSteps()).contains("Increase maximum-pool-size to 20");
        assertThat(report.investigationSteps()).hasSize(5);
        assertThat(report.investigationSteps().get(2).metadata()).containsEntry("bifrostResponseId", "chatcmpl-test");

        // Verify claims grounded in evidence registry
        assertThat(report.validatedClaims()).isNotEmpty();
        InvestigationClaim validatedPrimaryClaim = report.validatedClaims().stream()
                .filter(c -> c.claimId().startsWith("CLAIM-AGENT-PRIMARY"))
                .findFirst()
                .orElseThrow();
        assertThat(validatedPrimaryClaim.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validatedPrimaryClaim.supportingEvidenceIds()).contains("EVD-GIT-c0ffee1");
    }

    @Test
    @DisplayName("Should strictly reject hallucinated evidence IDs through DeterministicClaimValidator")
    void shouldRejectHallucinatedEvidenceIds() {
        UUID invId = UUID.randomUUID();

        // Register valid evidence EVD-01
        evidenceRegistry.registerItem(EvidenceItem.create(
                "EVD-REAL-01", EvidenceType.COMMIT_DIFF, "git", "sha1",
                Instant.now(), "Valid commit diff", Map.of("sha", "sha1")
        ));

        // Create a claim citing a hallucinated evidence ID
        InvestigationClaim hallucinatedClaim = InvestigationClaim.create(
                "CLAIM-HALLUCINATED-01",
                invId,
                "Hallucinated root cause assertion",
                List.of("EVD-NONEXISTENT-999"),
                List.of()
        );

        InvestigationClaim validated = claimValidator.validateClaim(hallucinatedClaim, invId, evidenceRegistry);

        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(validated.explanation()).contains("was not found in the active evidence registry");
    }

    @Test
    @DisplayName("Should isolate external research findings from local EvidenceRegistry")
    void shouldIsolateExternalResearchFromEvidenceRegistry() {
        UUID invId = UUID.randomUUID();

        ParsedException pe = new ParsedException("java.lang.NullPointerException", "Cannot invoke method", List.of(), null);

        InvestigationResult deterministicResult = new InvestigationResult(
                invId, "payment-service", "prod",
                Instant.now().minusSeconds(10), Instant.now(), "COMPLETED",
                1, 1, pe, List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), List.of(), false
        );

        ExternalResearchFinding tavilyFinding = ExternalResearchFinding.create(
                "NullPointerException in Spring Boot",
                "StackOverflow Discussion",
                "https://stackoverflow.com/questions/12345",
                "Common cause is uninitialized autowired bean",
                0.9
        );
        when(mockTavilyService.isAvailable()).thenReturn(true);
        when(mockTavilyService.research(any(), any())).thenReturn(List.of(tavilyFinding));
        when(mockBifrostClient.isAvailable()).thenReturn(false);

        InvestigationAgentReport report = agent.investigate(deterministicResult);

        // Research finding appears in report
        assertThat(report.externalResearchFindings()).hasSize(1);
        String findingId = report.externalResearchFindings().get(0).findingId();

        // INVARIANT: Research finding MUST NOT be in local EvidenceRegistry
        assertThat(evidenceRegistry.findItemById(findingId)).isEmpty();
    }

    @Test
    @DisplayName("Should fallback cleanly to deterministic reasoning when Bifrost gateway is unavailable")
    void shouldFallbackToDeterministicReasoningWhenBifrostUnavailable() {
        UUID invId = UUID.randomUUID();

        Event commitEvent = new Event(UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB,
                "auth-service", "prod", Instant.now(), "deadbeef", "{}");
        Incident incident = new Incident();
        incident.setId(invId);

        Hypothesis hypothesis = new Hypothesis(
                UUID.randomUUID(), incident, commitEvent, 1, 0.65,
                HypothesisStatus.PROPOSED, "Modified JWT filter logic", "Check token validation tests", null
        );

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                20.0, 15.0, 10.0, 0.0,
                List.of("Temporal correlation"), List.of()
        );

        InvestigationResult deterministicResult = new InvestigationResult(
                invId, "auth-service", "staging",
                Instant.now().minusSeconds(10), Instant.now(), "COMPLETED",
                3, 2, null, List.of(), List.of(), List.of(),
                List.of(hypothesis),
                Map.of("deadbeef", List.of()),
                Map.of(),
                Map.of("deadbeef", breakdown),
                List.of(),
                true
        );

        // Both external services unavailable
        when(mockBifrostClient.isAvailable()).thenReturn(false);
        when(mockTavilyService.isAvailable()).thenReturn(false);

        InvestigationAgentReport report = agent.investigate(deterministicResult);

        assertThat(report).isNotNull();
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
        assertThat(report.summary()).contains("deadbeef");
        assertThat(report.aiReasoningSummary()).contains("Deterministic Fallback Analysis");
        assertThat(report.externalResearchFindings()).isEmpty();
        assertThat(report.investigationSteps()).hasSize(5);
        assertThat(report.investigationSteps().stream()
                .anyMatch(s -> s.description().contains("Deterministic fallback reasoning"))).isTrue();
    }

    @Test
    @DisplayName("Should capture bifrostResponseId in step metadata when returned by AI Gateway")
    void shouldCaptureBifrostResponseIdInStepMetadata() {
        UUID invId = UUID.randomUUID();
        InvestigationResult result = createMinimalResult(invId);

        when(mockBifrostClient.isAvailable()).thenReturn(true);
        BifrostResponse aiResponse = new BifrostResponse(
                "chatcmpl-corr-999", "chat.completion", 12345, "gpt-4o",
                List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant("SUMMARY: Test.\nROOT CAUSE: Test."), "stop")),
                new BifrostResponse.Usage(10, 10, 20)
        );
        when(mockBifrostClient.chatCompletion(any(BifrostRequest.class))).thenReturn(aiResponse);

        InvestigationAgentReport report = agent.investigate(result);
        AgentInvestigationStep bifrostStep = report.investigationSteps().stream()
                .filter(s -> "Bifrost AI Gateway Reasoning".equals(s.stepName()))
                .findFirst()
                .orElseThrow();

        assertThat(bifrostStep.metadata()).containsEntry("bifrostResponseId", "chatcmpl-corr-999");
    }

    @Test
    @DisplayName("Should safely handle null or blank bifrost response ID without leaking or throwing")
    void shouldSafelyHandleNullOrBlankBifrostResponseId() {
        UUID invId = UUID.randomUUID();
        InvestigationResult result = createMinimalResult(invId);

        when(mockBifrostClient.isAvailable()).thenReturn(true);
        BifrostResponse aiResponse = new BifrostResponse(
                null, "chat.completion", 12345, "gpt-4o",
                List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant("SUMMARY: Test.\nROOT CAUSE: Test."), "stop")),
                new BifrostResponse.Usage(10, 10, 20)
        );
        when(mockBifrostClient.chatCompletion(any(BifrostRequest.class))).thenReturn(aiResponse);

        InvestigationAgentReport report = agent.investigate(result);
        AgentInvestigationStep bifrostStep = report.investigationSteps().stream()
                .filter(s -> "Bifrost AI Gateway Reasoning".equals(s.stepName()))
                .findFirst()
                .orElseThrow();

        assertThat(bifrostStep.metadata()).doesNotContainKey("bifrostResponseId");
        assertThat(bifrostStep.metadata()).containsEntry("status", "SUCCESS");
    }

    private InvestigationResult createMinimalResult(UUID invId) {
        return new InvestigationResult(
                invId, "test-service", "test-env",
                Instant.now().minusSeconds(10), Instant.now(), "COMPLETED",
                1, 1, null, List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), Map.of(), List.of(), true
        );
    }
}
