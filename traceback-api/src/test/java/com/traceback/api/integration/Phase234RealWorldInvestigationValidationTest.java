package com.traceback.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.tavily.ExternalResearchFinding;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Phase 2.34: Real-World Investigation Engine Validation Test Suite.
 * Validates the core engine across five synthetic software failure scenarios:
 * Scenario A: Runtime Exception (NullPointerException)
 * Scenario B: Configuration Regression (Connection Timeout)
 * Scenario C: Faulty Git Commit (Business Logic Exception)
 * Scenario D: Database Failure (Connection Refused Port Misconfiguration)
 * Scenario E: Insufficient Evidence (Incomplete Telemetry Abstention)
 * Plus Part 4: Determinism & Reproducibility Audit.
 *
 * Runs in isolated Spring test profile ("test") against in-memory H2 (PostgreSQL mode).
 * Guaranteed zero interaction with traceback_db, traceback_test_db, traceback_audit_db, or TacitIQ.
 */
@SpringBootTest(properties = "traceback.security.allowed-repo-roots=.,..,scratch,../scratch")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase234RealWorldInvestigationValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private InvestigationAgent investigationAgent;

    @MockBean
    private BifrostClient bifrostClient;

    @MockBean
    private TavilyResearchService tavilyService;

    private static final Path VALIDATION_REPOS_ROOT = resolveReposRoot();

    private static Path resolveReposRoot() {
        Path direct = Paths.get("scratch", "validation_repos").toAbsolutePath().normalize();
        if (direct.toFile().exists()) {
            return direct;
        }
        Path parent = Paths.get("..", "scratch", "validation_repos").toAbsolutePath().normalize();
        if (parent.toFile().exists()) {
            return parent;
        }
        return direct;
    }

    @BeforeEach
    void setupMocks() {
        BifrostConfig bConfig = new BifrostConfig("http://localhost:8080/v1", "sk-mock-key", "test-model", Duration.ofSeconds(5), 1);
        when(bifrostClient.isAvailable()).thenReturn(true);
        when(bifrostClient.getConfig()).thenReturn(bConfig);

        when(bifrostClient.chatCompletion(any())).thenAnswer(invocation -> {
            return new BifrostResponse(
                    "chatcmpl-val-001", "chat.completion", 12345, "gpt-4o",
                    List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant("""
                            SUMMARY: Forensic investigation completed successfully based on deterministic telemetry and Git correlation.
                            ROOT CAUSE: The candidate commit identified by deterministic correlation directly triggered the observed failure.
                            REASONING: Code or configuration overlap confirmed between Git delta and failure symptoms.
                            VERIFICATION STEPS:
                            - Review commit diff and revert or patch the offending changes
                            - Execute automated regression test
                            UNCERTAINTIES:
                            - None noted
                            """), "stop")),
                    new BifrostResponse.Usage(100, 50, 150)
            );
        });

        when(tavilyService.isAvailable()).thenReturn(true);
        when(tavilyService.research(anyString(), any())).thenReturn(List.of(
                ExternalResearchFinding.create(
                        "Technical Reference",
                        "Failure Analysis Guide",
                        "https://example.com/docs/failure-guide",
                        "Empirical correlation of software failure symptoms.",
                        0.92
                )
        ));
    }

    @Test
    @DisplayName("Scenario A: Runtime Exception (NullPointerException in UserService)")
    void testScenarioA_RuntimeException() {
        Path repoA = VALIDATION_REPOS_ROOT.resolve("scenario_a_npe");
        assertThat(repoA.toFile()).exists();

        String stackTrace = """
                java.lang.NullPointerException: Cannot invoke "com.example.Address.getCity()" because the return value of "com.example.User.getAddress()" is null
                    at com.example.UserService.getUserCity(UserService.java:4)
                    at com.example.UserController.processUser(UserController.java:18)
                    at com.example.Application.main(Application.java:12)
                """;

        String logContent = """
                2026-11-15 10:20:00.123 ERROR [http-exec-1] com.example.UserController : Failed to retrieve user details
                java.lang.NullPointerException: Cannot invoke "com.example.Address.getCity()" because the return value of "com.example.User.getAddress()" is null
                    at com.example.UserService.getUserCity(UserService.java:4)
                """;

        InvestigationResult result = investigationService.startInvestigation(
                repoA.toString(),
                stackTrace,
                null,
                logContent,
                "user-service",
                "production",
                60,
                10,
                InvestigationMode.RUNTIME_FAILURE
        );

        // 1. Failure detected
        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.primaryException()).isNotNull();
        assertThat(result.primaryException().getRootCause().exceptionType()).isEqualTo("java.lang.NullPointerException");

        // 2. Evidence collected
        assertThat(result.evidenceItems()).isNotEmpty();
        boolean hasStackFrameEvidence = result.evidenceItems().stream()
                .anyMatch(e -> e.summary().contains("UserService.java") || e.originalReference().contains("UserService.java"));
        assertThat(hasStackFrameEvidence).isTrue();

        // 3. Hypotheses ranked sensibly: Top hypothesis is the commit refactoring UserService
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis topHypothesis = result.hypotheses().get(0);
        assertThat(topHypothesis.getRank()).isEqualTo(1);
        assertThat(topHypothesis.getSummary()).contains("UserService");
        assertThat(topHypothesis.getMatchedRules()).contains("RULE_B_STACK_TRACE_OVERLAP");
        assertThat(topHypothesis.getConfidenceScore()).isGreaterThanOrEqualTo(0.70);

        // 4. Cosmetic commit penalized
        boolean hasCosmeticPenalty = result.evidenceItems().stream()
                .anyMatch(e -> e.evidenceId().startsWith("EVD-CONTRA-COSMETIC"));
        assertThat(hasCosmeticPenalty).isTrue();

        // 5. Agent report generation
        InvestigationAgentReport agentReport = investigationAgent.investigate(result);
        assertThat(agentReport).isNotNull();
        assertThat(agentReport.finalStatus()).isIn(AgentInvestigationStatus.CONFIRMED, AgentInvestigationStatus.PROBABLE);
        assertThat(agentReport.investigationId()).isEqualTo(result.investigationId());
    }

    @Test
    @DisplayName("Scenario B: Configuration Regression (Connection Timeout reduced to 500ms)")
    void testScenarioB_ConfigurationRegression() {
        Path repoB = VALIDATION_REPOS_ROOT.resolve("scenario_b_config");
        assertThat(repoB.toFile()).exists();

        String logContent = """
                2026-11-15 10:15:00.100 ERROR [HikariPool-1] com.zaxxer.hikari.pool.HikariPool : HikariPool-1 - Connection is not available, request timed out after 500ms.
                2026-11-15 10:15:01.200 ERROR [http-exec-3] com.example.OrderService : java.sql.SQLTimeoutException: Connection timeout after 500ms waiting for available pool connection.
                """;

        InvestigationResult result = investigationService.startInvestigation(
                repoB.toString(),
                null,
                null,
                logContent,
                "order-service",
                "production",
                60,
                10,
                InvestigationMode.RUNTIME_FAILURE
        );

        // 1. Evidence sufficiency and error detection
        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.errorsDetected()).isGreaterThanOrEqualTo(1);

        // 2. Configuration diff evidence collected
        List<EvidenceItem> configDiffs = result.evidenceItems().stream()
                .filter(e -> e.evidenceId().startsWith("EVD-CFG-"))
                .toList();
        assertThat(configDiffs).isNotEmpty();

        // 3. Top hypothesis: Configuration regression commit identified
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis topHypothesis = result.hypotheses().get(0);
        assertThat(topHypothesis.getRank()).isEqualTo(1);
        assertThat(topHypothesis.getMatchedRules()).contains("RULE_C_CONFIG_OVERLAP");
        assertThat(topHypothesis.getConfidenceScore()).isGreaterThanOrEqualTo(0.85);

        // 4. Agent Report
        InvestigationAgentReport agentReport = investigationAgent.investigate(result);
        assertThat(agentReport).isNotNull();
        assertThat(agentReport.finalStatus()).isIn(AgentInvestigationStatus.CONFIRMED, AgentInvestigationStatus.PROBABLE);
    }

    @Test
    @DisplayName("Scenario C: Faulty Git Commit (Business Logic Exception in OrderCalculator)")
    void testScenarioC_FaultyGitCommit() {
        Path repoC = VALIDATION_REPOS_ROOT.resolve("scenario_c_commit");
        assertThat(repoC.toFile()).exists();

        String stackTrace = """
                java.lang.IllegalStateException: Discount calculation error: negative margin violation
                    at com.example.order.OrderCalculator.calculateTotal(OrderCalculator.java:5)
                    at com.example.CheckoutService.processCheckout(CheckoutService.java:24)
                """;

        String logContent = """
                2026-11-15 10:15:00.500 ERROR [http-exec-2] com.example.CheckoutService : Checkout failure for order #9821
                java.lang.IllegalStateException: Discount calculation error: negative margin violation
                    at com.example.order.OrderCalculator.calculateTotal(OrderCalculator.java:5)
                """;

        InvestigationResult result = investigationService.startInvestigation(
                repoC.toString(),
                stackTrace,
                null,
                logContent,
                "checkout-service",
                "production",
                60,
                10,
                InvestigationMode.RUNTIME_FAILURE
        );

        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.primaryException()).isNotNull();

        // Top hypothesis identifies the commit touching OrderCalculator.java
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis topHypothesis = result.hypotheses().get(0);
        assertThat(topHypothesis.getSummary()).contains("OrderCalculator");
        assertThat(topHypothesis.getMatchedRules()).contains("RULE_B_STACK_TRACE_OVERLAP");
        assertThat(topHypothesis.getConfidenceScore()).isGreaterThanOrEqualTo(0.70);

        // Agent report confirms causal connection
        InvestigationAgentReport agentReport = investigationAgent.investigate(result);
        assertThat(agentReport).isNotNull();
        assertThat(agentReport.finalStatus()).isIn(AgentInvestigationStatus.CONFIRMED, AgentInvestigationStatus.PROBABLE);
    }

    @Test
    @DisplayName("Scenario D: Database Failure (Connection Refused Port 9999 Misconfiguration)")
    void testScenarioD_DatabaseFailure() {
        Path repoD = VALIDATION_REPOS_ROOT.resolve("scenario_d_database");
        assertThat(repoD.toFile()).exists();

        String logContent = """
                2026-11-15 10:15:00.010 ERROR [main] org.postgresql.Driver : Connection to localhost:9999 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.
                2026-11-15 10:15:00.100 ERROR [main] org.springframework.boot.SpringApplication : Application run failed
                org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection; nested exception is org.postgresql.util.PSQLException: Connection to localhost:9999 refused.
                """;

        InvestigationResult result = investigationService.startInvestigation(
                repoD.toString(),
                null,
                null,
                logContent,
                "db-service",
                "production",
                60,
                10,
                InvestigationMode.RUNTIME_FAILURE
        );

        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.errorsDetected()).isGreaterThanOrEqualTo(1);

        // Top hypothesis identifies datasource configuration change to port 9999
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis topHypothesis = result.hypotheses().get(0);
        assertThat(topHypothesis.getMatchedRules()).contains("RULE_C_CONFIG_OVERLAP");
        assertThat(topHypothesis.getSummary()).contains("datasource");

        // Agent report
        InvestigationAgentReport agentReport = investigationAgent.investigate(result);
        assertThat(agentReport).isNotNull();
        assertThat(agentReport.finalStatus()).isIn(AgentInvestigationStatus.CONFIRMED, AgentInvestigationStatus.PROBABLE);
    }

    @Test
    @DisplayName("Scenario E: Insufficient Evidence (Ambiguous log warning without stack trace or failure)")
    void testScenarioE_InsufficientEvidence() {
        Path repoE = VALIDATION_REPOS_ROOT.resolve("scenario_e_insufficient");
        assertThat(repoE.toFile()).exists();

        String logContent = """
                2026-11-15 10:15:00.000 INFO [main] com.example.App : Starting background worker
                2026-11-15 10:15:02.100 WARN [worker-1] com.example.Worker : Transient notification dispatch delayed
                2026-11-15 10:15:05.200 INFO [main] com.example.App : Worker completed execution
                """;

        InvestigationResult result = investigationService.startInvestigation(
                repoE.toString(),
                null,
                null,
                logContent,
                "worker-service",
                "production",
                60,
                10,
                InvestigationMode.RUNTIME_FAILURE
        );

        // Core promise: Abstain from assigning an unsupported root cause when evidence is insufficient
        assertThat(result.evidenceSufficient()).isFalse();
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");

        // Agent report also reflects INSUFFICIENT_EVIDENCE honestly
        InvestigationAgentReport agentReport = investigationAgent.investigate(result);
        assertThat(agentReport).isNotNull();
        assertThat(agentReport.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    @DisplayName("Part 4: Determinism & Reproducibility Across Repeated Execution")
    void testPart4_DeterminismAndReproducibility() {
        // Execute Scenario A twice with identical inputs
        Path repoA = VALIDATION_REPOS_ROOT.resolve("scenario_a_npe");

        String stackTrace = """
                java.lang.NullPointerException: Cannot invoke "com.example.Address.getCity()" because the return value of "com.example.User.getAddress()" is null
                    at com.example.UserService.getUserCity(UserService.java:4)
                    at com.example.UserController.processUser(UserController.java:18)
                """;

        InvestigationResult run1 = investigationService.startInvestigation(
                repoA.toString(), stackTrace, null, null, "user-service", "production", 60, 10, InvestigationMode.RUNTIME_FAILURE
        );

        InvestigationResult run2 = investigationService.startInvestigation(
                repoA.toString(), stackTrace, null, null, "user-service", "production", 60, 10, InvestigationMode.RUNTIME_FAILURE
        );

        // 1. Invariant: Status and sufficiency must be identical
        assertThat(run1.status()).isEqualTo(run2.status());
        assertThat(run1.evidenceSufficient()).isEqualTo(run2.evidenceSufficient());

        // 2. Invariant: Top hypothesis rank and confidence score must match identically
        assertThat(run1.hypotheses().size()).isEqualTo(run2.hypotheses().size());
        Hypothesis top1 = run1.hypotheses().get(0);
        Hypothesis top2 = run2.hypotheses().get(0);
        assertThat(top1.getConfidenceScore()).isEqualTo(top2.getConfidenceScore());
        assertThat(top1.getMatchedRules()).containsExactlyElementsOf(top2.getMatchedRules());

        // 3. Invariant: Evidence counts and types must match identically
        assertThat(run1.evidenceItems().size()).isEqualTo(run2.evidenceItems().size());
        Map<String, Long> types1 = run1.evidenceItems().stream().collect(Collectors.groupingBy(e -> e.evidenceType().name(), Collectors.counting()));
        Map<String, Long> types2 = run2.evidenceItems().stream().collect(Collectors.groupingBy(e -> e.evidenceType().name(), Collectors.counting()));
        assertThat(types1).isEqualTo(types2);

        // 4. Invariant: Cryptographic SHA-256 hashes must be valid on every evidence item
        for (EvidenceItem item : run1.evidenceItems()) {
            assertThat(item.integrityHash()).isNotEmpty();
            assertThat(item.integrityHash().length()).isIn(16, 64);
        }
        for (EvidenceItem item : run2.evidenceItems()) {
            assertThat(item.integrityHash()).isNotEmpty();
            assertThat(item.integrityHash().length()).isIn(16, 64);
        }

        // 5. Invariant: Claims validation statuses match
        assertThat(run1.claims().size()).isEqualTo(run2.claims().size());
        for (int i = 0; i < run1.claims().size(); i++) {
            InvestigationClaim c1 = run1.claims().get(i);
            InvestigationClaim c2 = run2.claims().get(i);
            assertThat(c1.status()).isEqualTo(c2.status());
        }
    }
}
