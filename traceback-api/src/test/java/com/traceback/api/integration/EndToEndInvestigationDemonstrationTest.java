package com.traceback.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.AgentInvestigationRequest;
import com.traceback.api.dto.InvestigationRequest;
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
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.validation.ClaimValidationStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 End-to-End System Demonstration Test.
 * Validates the full Traceback 2.0 autonomous software investigation workflow:
 * 1. Telemetry & Log Ingestion
 * 2. Real Git Analysis via JGit
 * 3. Stack Trace & Log Error Analysis
 * 4. Deterministic Correlation (S = T + O + A - C)
 * 5. Evidence Validation & Cryptographic SHA-256 Hashes
 * 6. Autonomous AI Reasoning via Bifrost Gateway
 * 7. External Technical Research via Tavily
 * 8. Strict Claim Grounding
 * 9. Interactive Frontend Investigation API Contract
 * 10. Honest Insufficient-Evidence Decisions
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndToEndInvestigationDemonstrationTest {

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

    @Test
    @DisplayName("Capabilities 1-9: Full End-to-End Investigation with Misleading Commit Filtering & Grounded AI Report")
    void shouldExecuteFullInvestigationDemonstration(@TempDir Path tempRepo) throws Exception {
        // --- 1. Set up real Git repository with a root cause commit and a misleading auth commit ---
        TimeZone utc = TimeZone.getTimeZone("UTC");
        PersonIdent devopsIdent = new PersonIdent("DevOps Engineer", "devops@example.com",
                Date.from(Instant.parse("2026-10-10T13:45:00Z")), utc);
        PersonIdent aliceIdent = new PersonIdent("Alice", "alice@example.com",
                Date.from(Instant.parse("2026-10-10T14:10:00Z")), utc);
        PersonIdent bobIdent = new PersonIdent("Bob", "bob@example.com",
                Date.from(Instant.parse("2026-10-10T14:14:00Z")), utc);

        String rootCauseSha;
        String misleadingSha;

        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            // Commit 1: Database connection timeout = 30 seconds
            Path configFile = tempRepo.resolve("application.properties");
            Files.writeString(configFile, "spring.datasource.hikari.connection-timeout=30000\nauth.login.message=Welcome\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Initial configuration with 30s connection timeout")
                    .setAuthor(devopsIdent).setCommitter(devopsIdent).call();

            // Commit 2: Root cause - Database connection timeout reduced to 3 seconds
            Files.writeString(configFile, "spring.datasource.hikari.connection-timeout=3000\nauth.login.message=Welcome\n");
            git.add().addFilepattern("application.properties").call();
            rootCauseSha = git.commit().setMessage("Tune database connection timeout to 3s")
                    .setAuthor(aliceIdent).setCommitter(aliceIdent).call().getName();

            // Commit 3: Misleading recent commit - Authentication log message renamed without changing database logic
            Path authFile = tempRepo.resolve("AuthService.java");
            Files.writeString(authFile, """
                    package com.traceback.auth;
                    public class AuthService {
                        public void logAuth() {
                            System.out.println("User signed in successfully to portal");
                        }
                    }
                    """);
            git.add().addFilepattern("AuthService.java").call();
            misleadingSha = git.commit().setMessage("Rename authentication sign-in message in AuthService")
                    .setAuthor(bobIdent).setCommitter(bobIdent).call().getName();
        }

        // --- 2. Telemetry Ingestion: Runtime logs exhibiting Hikari connection timeout failures ---
        String runtimeLogs = """
                2026-10-10 14:15:00.000 INFO 100 --- [main] c.t.PaymentApp : Service deployed and started successfully
                2026-10-10 14:16:35.100 ERROR 100 --- [HikariPool-1] org.postgresql.Driver : java.sql.SQLTimeoutException: Connection timeout after 3000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                2026-10-10 14:16:36.200 ERROR 100 --- [http-nio-exec-1] c.t.p.PaymentService : HikariPool-1 - Connection is not available, request timed out after 3000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                """;

        // --- 3. Mock Bifrost AI Gateway & Tavily Technical Research ---
        BifrostConfig bConfig = new BifrostConfig("http://localhost:8080/v1", "sk-bf-mock", "gpt-4o", Duration.ofSeconds(5), 1);
        when(bifrostClient.isAvailable()).thenReturn(true);
        when(bifrostClient.getConfig()).thenReturn(bConfig);

        String mockAiResponse = """
                SUMMARY: Investigation determined that commit %s aggressively reduced Hikari connection timeout from 30s to 3s, causing SQLTimeoutException under normal load.
                ROOT CAUSE: Commit %s lowered database connection timeout to 3000ms in application.properties.
                REASONING: The temporal window and configuration delta directly correlate with the crash logs. The latest commit (%s) was cosmetic documentation and refuted.
                VERIFICATION STEPS:
                - Inspect application.properties diff for commit %s
                - Revert connection-timeout to 30000ms
                - Run load tests against PostgreSQL datasource
                UNCERTAINTIES:
                - Verify if downstream database connection pool was also saturated
                """.formatted(rootCauseSha.substring(0, 7), rootCauseSha.substring(0, 7), misleadingSha.substring(0, 7), rootCauseSha.substring(0, 7));

        when(bifrostClient.chatCompletion(any())).thenReturn(new BifrostResponse(
                "chatcmpl-demo-001", "chat.completion", 12345, "gpt-4o",
                List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(mockAiResponse), "stop")),
                new BifrostResponse.Usage(150, 75, 225)
        ));

        when(tavilyService.isAvailable()).thenReturn(true);
        when(tavilyService.research(anyString(), any())).thenReturn(List.of(
                ExternalResearchFinding.create(
                        "HikariCP connection timeout configuration guide",
                        "HikariCP Configuration Best Practices",
                        "https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing",
                        "Setting connectionTimeout too low (< 5000ms) causes premature SQLTimeoutException under thread contention.",
                        0.94
                )
        ));

        // --- 4. Submit Investigation via POST /api/investigations ---
        InvestigationRequest requestDto = new InvestigationRequest(
                tempRepo.toString(),
                null,
                null,
                runtimeLogs,
                "payment-service",
                "production",
                60,
                10
        );

        MvcResult createResult = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.investigationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.serviceName").value("payment-service"))
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID invId = UUID.fromString(createdJson.get("investigationId").asText());

        // --- 5. Verify Deterministic Results & Root Cause Correlation ---
        InvestigationResult invResult = investigationService.getInvestigation(invId).orElseThrow();
        assertThat(invResult.commitsAnalyzed()).isEqualTo(3);
        assertThat(invResult.errorsDetected()).isGreaterThanOrEqualTo(1);
        assertThat(invResult.evidenceSufficient()).isTrue();

        // Leading hypothesis MUST be the timeout reduction commit, NOT the misleading latest commit
        Hypothesis topHypothesis = invResult.hypotheses().get(0);
        assertThat(topHypothesis.getRank()).isEqualTo(1);
        assertThat(topHypothesis.getSummary()).contains("Tune database connection timeout to 3s");
        assertThat(topHypothesis.getSummary()).contains(rootCauseSha.substring(0, 7));
        assertThat(topHypothesis.getConfidenceScore()).isGreaterThanOrEqualTo(0.85);

        // Verify cryptographic evidence hashes (Full 64-character SHA-256 hex or legacy 16-character prefix)
        assertThat(invResult.evidenceItems()).isNotEmpty();
        for (EvidenceItem item : invResult.evidenceItems()) {
            assertThat(item.integrityHash()).isNotEmpty();
            assertThat(item.integrityHash().length()).isIn(16, 64);
        }

        // --- 6. Trigger AI Agent Investigation via POST /api/investigations/agent ---
        AgentInvestigationRequest agentReq = new AgentInvestigationRequest(
                invId, null, null, null, null, null, null, null, null
        );
        mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationId").value(invId.toString()))
                .andExpect(jsonPath("$.reportId").isNotEmpty())
                .andExpect(jsonPath("$.finalStatus").value("CONFIRMED"));

        // --- 7. Verify Grounded AI Report & Claim Validation ---
        InvestigationAgentReport report = investigationAgent.getReport(invId).orElseThrow();
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.CONFIRMED);
        assertThat(report.primarySuspectedRootCause()).contains(rootCauseSha.substring(0, 7));
        assertThat(report.validatedClaims()).isNotEmpty();
        // Root cause commit claim MUST be SUPPORTED by verified evidence
        assertThat(report.validatedClaims()).anyMatch(c -> c.claimId().contains(rootCauseSha.substring(0, 7))
                && c.status() == ClaimValidationStatus.SUPPORTED);
        // Misleading commit claim MUST be refuted with CONTRADICTED status
        assertThat(report.validatedClaims()).anyMatch(c -> c.claimId().contains(misleadingSha.substring(0, 7))
                && c.status() == ClaimValidationStatus.CONTRADICTED);

        // Verify Tavily findings incorporated
        assertThat(report.externalResearchFindings()).isNotEmpty();
        assertThat(report.externalResearchFindings().get(0).title()).contains("HikariCP");

        // Verify full agent audit trail
        assertThat(report.investigationSteps()).hasSizeGreaterThanOrEqualTo(5);

        // --- 8. Verify Frontend API Contract Consistency across all routes ---
        // Route 1: GET /api/investigations/{id}
        mockMvc.perform(get("/api/investigations/" + invId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationId").value(invId.toString()));

        // Route 2: GET /api/investigations/{id}/agent-report (and alias /agent/{id})
        mockMvc.perform(get("/api/investigations/" + invId + "/agent-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(report.reportId()));

        mockMvc.perform(get("/api/investigations/agent/" + invId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(report.reportId()));

        // Route 3: GET /api/investigations/{id}/research (and alias /agent/{id}/research)
        mockMvc.perform(get("/api/investigations/" + invId + "/research"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value(containsString("HikariCP")));

        mockMvc.perform(get("/api/investigations/agent/" + invId + "/research"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value(containsString("HikariCP")));

        // Route 4: GET /api/investigations/{id}/agent-steps (and alias /agent/{id}/audit)
        mockMvc.perform(get("/api/investigations/" + invId + "/agent-steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/investigations/agent/" + invId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Route 5: GET /api/investigations history list
        mockMvc.perform(get("/api/investigations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.investigationId == '" + invId + "')]").exists());
    }

    @Test
    @DisplayName("Capability 10: Honest Insufficient-Evidence Decisions & Safe Abstention")
    void shouldAbstainWhenEvidenceIsInsufficient(@TempDir Path tempRepo) throws Exception {
        // Create repo where commit has NOTHING to do with runtime failure
        TimeZone utc = TimeZone.getTimeZone("UTC");
        PersonIdent author = new PersonIdent("Designer", "ui@example.com",
                Date.from(Instant.parse("2026-10-10T14:00:00Z")), utc);

        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("style.css");
            Files.writeString(file, "body { background-color: #fff; }\n");
            git.add().addFilepattern("style.css").call();
            git.commit().setMessage("Update background color in CSS")
                    .setAuthor(author).setCommitter(author).call();
        }

        // Telemetry has NullPointerException in unrelated service code
        String crashLogs = """
                2026-10-10 14:15:00.000 ERROR 100 --- [http-nio-8080-exec-1] c.t.p.OrderController : java.lang.NullPointerException: Cannot invoke 'Order.getId()' because 'order' is null
                    at com.traceback.order.OrderService.processOrder(OrderService.java:85)
                    at com.traceback.order.OrderController.handle(OrderController.java:42)
                """;

        InvestigationRequest req = new InvestigationRequest(
                tempRepo.toString(),
                null,
                null,
                crashLogs,
                "order-service",
                "production",
                60,
                10
        );

        MvcResult createResult = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.evidenceSufficient").value(false))
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID invId = UUID.fromString(json.get("investigationId").asText());

        // Run agent
        when(bifrostClient.isAvailable()).thenReturn(false); // Deterministic agent fallback
        when(tavilyService.isAvailable()).thenReturn(false);

        AgentInvestigationRequest agentReq = new AgentInvestigationRequest(
                invId, null, null, null, null, null, null, null, null
        );

        mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalStatus").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.summary").value(containsString("insufficient evidence")));

        InvestigationAgentReport report = investigationAgent.getReport(invId).orElseThrow();
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(report.remainingUncertainties()).isNotEmpty();
    }

    @Test
    @DisplayName("Security Hardening: Reject Unauthorized Repository Paths with 403 Forbidden")
    void shouldRejectUnauthorizedRepositoryPath() throws Exception {
        // Path clearly outside authorized boundary
        String unauthorizedPath = "C:\\Windows\\System32\\unauthorized_repo";

        InvestigationRequest req = new InvestigationRequest(
                unauthorizedPath, null, null, null, "security-service", "production", null, null
        );

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(containsString("Access denied")));
    }
}
