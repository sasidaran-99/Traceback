package com.traceback.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.BifrostException;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostRequest;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.bifrost.DefaultBifrostClient;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCollector;
import com.traceback.core.git.workingtree.GitWorkingTreeCollector;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2.48: Investigation Reliability & Failure-Mode Audit Test Suite.
 * Rigorously evaluates pipeline behavior across 20+ isolated failure scenarios.
 */
class Phase248ReliabilityAndFailureModeAuditTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // 1. BIFROST & GEMINI FAILURE MODES
    // =========================================================================
    @Nested
    @DisplayName("1. Bifrost Gateway Failure Modes")
    class BifrostFailureModesTests {

        @Test
        @DisplayName("Scenario 1.1: Connection refused — retries bounded to maxAttempts then throws BifrostException")
        void testConnectionRefusedRetriesAndThrows() throws Exception {
            HttpClient mockHttp = mock(HttpClient.class);
            doThrow(new ConnectException("Connection refused: localhost:8181"))
                    .when(mockHttp).send(any(HttpRequest.class), any());

            BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-test", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
            DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

            assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of())))
                    .isInstanceOf(BifrostException.class)
                    .hasMessageContaining("failed after 3 attempts");

            verify(mockHttp, times(3)).send(any(), any());
        }

        @Test
        @DisplayName("Scenario 1.2: HTTP 401 Unauthorized — fails immediately without retrying")
        void testUnauthorizedFailsImmediatelyWithoutRetries() throws Exception {
            HttpClient mockHttp = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = (HttpResponse<String>) mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(401);
            when(mockResp.body()).thenReturn("{\"error\":\"Invalid virtual key\"}");
            doReturn(mockResp).when(mockHttp).send(any(), any());

            BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-bad-key", "gemini-3.8-flash", Duration.ofSeconds(1), 3);
            DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

            assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of())))
                    .isInstanceOf(BifrostException.class)
                    .hasMessageContaining("401");

            // Exactly 1 attempt: zero wasteful retries on auth failure
            verify(mockHttp, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("Scenario 1.3: HTTP 429 Rate Limiting — retries bounded to maxAttempts")
        void testRateLimitingRetriesUpToMaxAttempts() throws Exception {
            HttpClient mockHttp = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = (HttpResponse<String>) mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(429);
            when(mockResp.body()).thenReturn("{\"error\":\"Too many requests\"}");
            doReturn(mockResp).when(mockHttp).send(any(), any());

            BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-test", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
            DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

            assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of())))
                    .isInstanceOf(BifrostException.class)
                    .hasMessageContaining("429");

            verify(mockHttp, times(3)).send(any(), any());
        }

        @Test
        @DisplayName("Scenario 1.4: Gateway Timeout (HTTP 504) — retries bounded to maxAttempts")
        void testGatewayTimeoutRetries() throws Exception {
            HttpClient mockHttp = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = (HttpResponse<String>) mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(504);
            when(mockResp.body()).thenReturn("Gateway Timeout from upstream Gemini");
            doReturn(mockResp).when(mockHttp).send(any(), any());

            BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-test", "gemini-3.8-flash", Duration.ofSeconds(180), 1);
            DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

            assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of())))
                    .isInstanceOf(BifrostException.class)
                    .hasMessageContaining("504");

            verify(mockHttp, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("Scenario 1.5: Empty or blank AI completion text cleanly falls back to deterministic analysis")
        void testEmptyAiResponseFallsBackCleanly() {
            BifrostClient mockBifrost = mock(BifrostClient.class);
            TavilyResearchService mockTavily = mock(TavilyResearchService.class);
            when(mockBifrost.isAvailable()).thenReturn(true);
            when(mockBifrost.getConfig()).thenReturn(new BifrostConfig("http://localhost:8181", "sk-bf-test", "gemini-3.8-flash", Duration.ofSeconds(5), 0));

            // Return response with blank content
            BifrostResponse emptyResp = new BifrostResponse("cmpl-1", "chat.completion", 1000L, "gemini-3.8-flash",
                    List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(""), "stop")), null);
            when(mockBifrost.chatCompletion(any())).thenReturn(emptyResp);

            InvestigationAgent agent = new InvestigationAgent(
                    mockBifrost, mockTavily, new DeterministicClaimValidator(),
                    new ConcreteEvidenceRegistry(), mock(InvestigationService.class)
            );

            UUID invId = UUID.randomUUID();
            InvestigationResult result = new InvestigationResult(
                    invId, "order-service", "prod", Instant.now(), Instant.now(), "INSUFFICIENT_EVIDENCE",
                    0, 0, null, List.of(), List.of(), List.of(), List.of(),
                    Map.of(), Map.of(), Map.of(), List.of(), false
            );

            InvestigationAgentReport report = agent.investigate(result);
            assertThat(report).isNotNull();
            assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
            assertThat(report.summary()).contains("Available telemetry is inadequate");
        }

        @Test
        @DisplayName("Scenario 1.6: Bifrost unconfigured (missing key) executes deterministic fallback with audit step")
        void testUnconfiguredBifrostProducesDeterministicFallback() {
            BifrostClient unconfiguredBifrost = mock(BifrostClient.class);
            when(unconfiguredBifrost.isAvailable()).thenReturn(false);

            InvestigationAgent agent = new InvestigationAgent(
                    unconfiguredBifrost, mock(TavilyResearchService.class), new DeterministicClaimValidator(),
                    new ConcreteEvidenceRegistry(), mock(InvestigationService.class)
            );

            UUID invId = UUID.randomUUID();
            InvestigationResult result = new InvestigationResult(
                    invId, "payment-service", "prod", Instant.now(), Instant.now(), "INSUFFICIENT_EVIDENCE",
                    0, 0, null, List.of(), List.of(), List.of(), List.of(),
                    Map.of(), Map.of(), Map.of(), List.of(), false
            );

            InvestigationAgentReport report = agent.investigate(result);
            assertThat(report.bifrostExecutionStatus()).isEqualTo("UNCONFIGURED");
            assertThat(report.investigationSteps().stream()
                    .anyMatch(s -> s.description().contains("Deterministic fallback reasoning generated"))).isTrue();
        }
    }

    // =========================================================================
    // 2. INVESTIGATION PIPELINE FAILURE MODES
    // =========================================================================
    @Nested
    @DisplayName("2. Investigation Pipeline Failure Modes")
    class PipelineFailureModesTests {

        @Test
        @DisplayName("Scenario 2.1: Empty Git repository (no HEAD) handled without crash")
        void testEmptyGitRepositoryHandledGracefully(@TempDir Path tempRepo) throws Exception {
            // Init empty repo without any commits
            try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
                // No commits created
            }

            GitCollector collector = new GitCollector(List.of(tempRepo.getParent().toString()));
            List<CommitEvidence> commits = collector.collectCommits(tempRepo.toString(), 50);

            assertThat(commits).isEmpty();
        }

        @Test
        @DisplayName("Scenario 2.2: Non-existent repository path throws IllegalArgumentException")
        void testNonExistentRepositoryPathThrows(@TempDir Path tempDir) {
            GitCollector collector = new GitCollector(List.of(tempDir.toString()));
            Path missing = tempDir.resolve("non_existent_repo");

            assertThatThrownBy(() -> collector.validateRepositoryPath(missing.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist or is inaccessible");
        }

        @Test
        @DisplayName("Scenario 2.3: Repository path outside authorized roots throws SecurityException")
        void testUnauthorizedRepositoryPathThrows() {
            GitCollector collector = new GitCollector(List.of("C:\\Authorized\\Path"));

            assertThatThrownBy(() -> collector.validateRepositoryPath("C:\\Windows\\System32"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("not within the authorized repository roots");
        }

        @Test
        @DisplayName("Scenario 2.4: Log file exceeding 5MB rejected with IllegalArgumentException")
        void testOversizedLogFileRejected(@TempDir Path tempDir) throws IOException {
            Path canonicalTempDir = tempDir.toRealPath();
            Path largeLog = canonicalTempDir.resolve("oversized.log");
            byte[] fiveMbPlus = new byte[5 * 1024 * 1024 + 1024];
            Files.write(largeLog, fiveMbPlus);

            GitCollector collector = new GitCollector(List.of(canonicalTempDir.toString()));
            InvestigationService service = new InvestigationService(
                    collector, mock(GitWorkingTreeCollector.class), mock(StackTraceParser.class),
                    mock(LogAnalyzer.class), mock(TimelineBuilder.class),
                    mock(CorrelationEngine.class), new ConcreteEvidenceRegistry()
            );

            assertThatThrownBy(() -> service.startInvestigation(
                    null, null, largeLog.toString(), null,
                    "service", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum allowed size of 5MB");
        }

        @Test
        @DisplayName("Scenario 2.5: Zero telemetry inputs enforces honest abstention (INSUFFICIENT_EVIDENCE)")
        void testZeroTelemetryEnforcesHonestAbstention(@TempDir Path tempRepo) throws Exception {
            try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
                Path file = tempRepo.resolve("Code.java");
                Files.writeString(file, "class Code {}");
                git.add().addFilepattern("Code.java").call();
                git.commit().setMessage("Initial commit").call();
            }

            GitCollector collector = new GitCollector(List.of(tempRepo.getParent().toString()));
            CorrelationEngine correlationEngine = new CorrelationEngine();
            EvidenceRegistry registry = new ConcreteEvidenceRegistry();

            InvestigationService service = new InvestigationService(
                    collector, null, new StackTraceParser(),
                    new LogAnalyzer(), new TimelineBuilder(),
                    correlationEngine, registry
            );

            // No stack trace, no log content, no log file
            InvestigationResult result = service.startInvestigation(
                    tempRepo.toString(), null, null, null,
                    "test-service", "production", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );

            assertThat(result.evidenceSufficient()).isFalse();
            assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(result.hypotheses()).isNotEmpty();
            assertThat(result.hypotheses().getFirst().getSummary()).contains("INSUFFICIENT_EVIDENCE");
            assertThat(result.hypotheses().getFirst().getConfidenceScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Scenario 2.6: Unparsable / invalid stack trace falls back safely without crash")
        void testUnparsableStackTraceFallsBackSafely(@TempDir Path tempRepo) throws Exception {
            try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
                Path file = tempRepo.resolve("Code.java");
                Files.writeString(file, "class Code {}");
                git.add().addFilepattern("Code.java").call();
                git.commit().setMessage("Initial commit").call();
            }

            GitCollector collector = new GitCollector(List.of(tempRepo.getParent().toString()));
            InvestigationService service = new InvestigationService(
                    collector, null, new StackTraceParser(),
                    new LogAnalyzer(), new TimelineBuilder(),
                    new CorrelationEngine(), new ConcreteEvidenceRegistry()
            );

            InvestigationResult result = service.startInvestigation(
                    tempRepo.toString(), "This is random text that is not a valid stack trace", null, null,
                    "test-service", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );

            // Investigation completes safely with abstention instead of throwing unhandled parser exception
            assertThat(result).isNotNull();
            assertThat(result.evidenceSufficient()).isFalse();
            assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        }
    }

    // =========================================================================
    // 3. DETERMINISTIC SCORING & EVIDENCE GROUNDING FAILURES
    // =========================================================================
    @Nested
    @DisplayName("3. Deterministic Scoring & Evidence Grounding Failures")
    class ScoringAndGroundingFailuresTests {

        @Test
        @DisplayName("Scenario 3.1: AI claim citing hallucinated / non-existent evidence ID is strictly rejected")
        void testHallucinatedEvidenceIdRejected() {
            DeterministicClaimValidator validator = new DeterministicClaimValidator();
            EvidenceRegistry registry = new ConcreteEvidenceRegistry();
            UUID invId = UUID.randomUUID();

            InvestigationClaim claim = InvestigationClaim.create(
                    "CLAIM-AI-01",
                    invId,
                    "The database connection timed out due to pool exhaustion.",
                    List.of("EVD-NON-EXISTENT-99999"),
                    List.of()
            );

            InvestigationClaim validated = validator.validateClaim(claim, invId, registry);
            assertThat(validated.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
            assertThat(validated.explanation()).contains("was not found in the active evidence registry");
        }

        @Test
        @DisplayName("Scenario 3.2: AI claim citing foreign-investigation evidence is strictly rejected")
        void testForeignInvestigationEvidenceRejected() {
            DeterministicClaimValidator validator = new DeterministicClaimValidator();
            EvidenceRegistry registry = new ConcreteEvidenceRegistry();
            UUID currentInvId = UUID.randomUUID();
            UUID foreignInvId = UUID.randomUUID();

            // Register evidence under foreign investigation ID
            EvidenceItem foreignItem = EvidenceItem.create(
                    "EVD-FOREIGN-100",
                    EvidenceType.COMMIT,
                    "GIT",
                    "commit123",
                    Instant.now(),
                    "Foreign commit",
                    Map.of("investigationId", foreignInvId.toString(), "originatingInvestigationId", foreignInvId.toString())
            );
            registry.registerItem(foreignItem, foreignInvId);

            InvestigationClaim claim = InvestigationClaim.create(
                    "CLAIM-AI-02",
                    currentInvId,
                    "Foreign commit caused current failure.",
                    List.of("EVD-FOREIGN-100"),
                    List.of()
            );

            InvestigationClaim validated = validator.validateClaim(claim, currentInvId, registry);
            assertThat(validated.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
            assertThat(validated.explanation()).contains("belongs to a different investigation");
        }

        @Test
        @DisplayName("Scenario 3.3: AI cannot override deterministic score or verdict")
        void testAiCannotOverrideDeterministicVerdict() {
            BifrostClient mockBifrost = mock(BifrostClient.class);
            when(mockBifrost.isAvailable()).thenReturn(true);
            when(mockBifrost.getConfig()).thenReturn(new BifrostConfig("http://localhost:8181", "sk-bf-test", "gemini-3.8-flash", Duration.ofSeconds(5), 0));

            // Hallucinated AI response claiming 100% CONFIRMED on an insufficient evidence investigation
            String hallucinatedAiText = """
                    SUMMARY: Confirmed root cause found! Commit deadbeef directly triggered the crash!
                    ROOT CAUSE: Confirmed bug in payment handler.
                    VERIFICATION STEPS:
                    - Deploy hotfix immediately.
                    UNCERTAINTIES:
                    - None. 100% certain.
                    """;

            BifrostResponse aiResp = new BifrostResponse("cmpl-1", "chat.completion", 1000L, "gemini-3.8-flash",
                    List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(hallucinatedAiText), "stop")), null);
            when(mockBifrost.chatCompletion(any())).thenReturn(aiResp);

            InvestigationAgent agent = new InvestigationAgent(
                    mockBifrost, mock(TavilyResearchService.class), new DeterministicClaimValidator(),
                    new ConcreteEvidenceRegistry(), mock(InvestigationService.class)
            );

            UUID invId = UUID.randomUUID();
            InvestigationResult result = new InvestigationResult(
                    invId, "payment-service", "prod", Instant.now(), Instant.now(), "INSUFFICIENT_EVIDENCE",
                    1, 1, null, List.of(), List.of(), List.of(), List.of(),
                    Map.of(), Map.of(), Map.of(), List.of(), false
            );

            InvestigationAgentReport report = agent.investigate(result);

            // Invariant: Verdict MUST remain INSUFFICIENT_EVIDENCE; AI summary override is suppressed
            assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
            assertThat(report.summary()).doesNotContain("Confirmed root cause found!");
            assertThat(report.summary()).contains("Available telemetry is inadequate");
        }
    }

    // =========================================================================
    // 4. PERSISTENCE FAILURE MODES & RECOVERY
    // =========================================================================
    @Nested
    @DisplayName("4. Persistence Failure Modes & Isolation")
    class PersistenceFailureModesTests {

        @Test
        @DisplayName("Scenario 4.1: Database exception during evidence persistence logs error without crashing in-memory execution")
        void testEvidencePersistenceFailureDoesNotCrashService() {
            InvestigationEvidenceRepository mockEvidenceRepo = mock(InvestigationEvidenceRepository.class);
            doThrow(new RuntimeException("Database disk full")).when(mockEvidenceRepo).save(any());

            ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry(mockEvidenceRepo, new ObjectMapper());
            UUID invId = UUID.randomUUID();

            EvidenceItem item = EvidenceItem.create(
                    "EVD-STACK-ROOT",
                    EvidenceType.STACK_TRACE,
                    "STACK",
                    "NullPointerException",
                    Instant.now(),
                    "Root exception",
                    Map.of("investigationId", invId.toString(), "originatingInvestigationId", invId.toString())
            );

            // Should not throw: error is caught, logged, and in-memory cache remains populated
            EvidenceItem registered = registry.registerItem(item, invId);
            assertThat(registered).isNotNull();
            assertThat(registry.findItemById("EVD-STACK-ROOT", invId)).isPresent();
        }

        @Test
        @DisplayName("Scenario 4.2: InvestigationService database failure logs error while returning in-memory result")
        void testInvestigationServicePersistenceFailureReturnsInMemoryResult() {
            InvestigationRepository mockInvRepo = mock(InvestigationRepository.class);
            doThrow(new RuntimeException("Connection pool timeout")).when(mockInvRepo).save(any());

            InvestigationService service = new InvestigationService(
                    mock(GitCollector.class), null, new StackTraceParser(),
                    new LogAnalyzer(), new TimelineBuilder(),
                    new CorrelationEngine(), new ConcreteEvidenceRegistry()
            );
            service.setInvestigationRepository(mockInvRepo);
            service.setPersistenceMapper(new InvestigationPersistenceMapper());

            InvestigationResult result = service.startInvestigation(
                    null, "java.lang.NullPointerException\n\tat com.test.Service.run(Service.java:10)", null, null,
                    "service", "prod", 60, 50, InvestigationMode.RUNTIME_FAILURE
            );

            // In-memory result is returned safely despite DB save failure
            assertThat(result).isNotNull();
            assertThat(service.getInvestigation(result.investigationId())).isPresent();
        }

        @Test
        @DisplayName("Scenario 4.3: InvestigationAgent report persistence failure does not lose in-memory report")
        void testAgentReportPersistenceFailurePreservesInMemoryReport() {
            InvestigationAgentReportRepository mockReportRepo = mock(InvestigationAgentReportRepository.class);
            doThrow(new RuntimeException("PostgreSQL connection terminated")).when(mockReportRepo).save(any());

            InvestigationAgent agent = new InvestigationAgent(
                    mock(BifrostClient.class), mock(TavilyResearchService.class), new DeterministicClaimValidator(),
                    new ConcreteEvidenceRegistry(), mock(InvestigationService.class)
            );
            agent.setAgentReportRepository(mockReportRepo);

            UUID invId = UUID.randomUUID();
            InvestigationResult result = new InvestigationResult(
                    invId, "service", "prod", Instant.now(), Instant.now(), "INSUFFICIENT_EVIDENCE",
                    0, 0, null, List.of(), List.of(), List.of(), List.of(),
                    Map.of(), Map.of(), Map.of(), List.of(), false
            );

            InvestigationAgentReport report = agent.investigate(result);
            assertThat(report).isNotNull();
            assertThat(agent.getReport(invId)).isPresent();
            assertThat(agent.getReport(invId).get().reportId()).isEqualTo(report.reportId());
        }
    }
}
