package com.traceback.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.tavily.ExternalResearchFinding;
import com.traceback.core.agent.tavily.TavilyResearchService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "OPERATOR")
class AgentInvestigationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BifrostClient bifrostClient;

    @MockBean
    private TavilyResearchService tavilyService;

    @Test
    @DisplayName("End-to-End Agent API Flow: POST /agent, GET /agent-report, GET /research, GET /agent-steps")
    void shouldExecuteAgentInvestigationWorkflow(@TempDir Path tempRepo) throws Exception {
        // 1. Create a minimal valid Git repository with commit
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        org.eclipse.jgit.lib.PersonIdent author = new org.eclipse.jgit.lib.PersonIdent("Engineer", "eng@example.com",
                java.util.Date.from(java.time.Instant.parse("2026-10-10T14:10:00Z")), utc);

        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("application.yml");
            Files.writeString(file, "spring:\n  datasource:\n    hikari:\n      maximum-pool-size: 2\n");
            git.add().addFilepattern("application.yml").call();
            git.commit().setMessage("Reduce pool size to 2").setAuthor(author).setCommitter(author).call();
        }

        // Mock Bifrost Gateway
        BifrostConfig bConfig = new BifrostConfig("http://localhost:8080/v1", "sk-bf-mock", "gpt-4o", Duration.ofSeconds(5), 1);
        when(bifrostClient.isAvailable()).thenReturn(true);
        when(bifrostClient.getConfig()).thenReturn(bConfig);

        String mockAiText = """
                SUMMARY: Database connection pool was starved by aggressive pool size reduction.
                ROOT CAUSE: Commit reduced pool size to 2 in application.yml.
                REASONING: Code analysis reveals maximum-pool-size was lowered right before timeout failures.
                VERIFICATION STEPS:
                - Inspect application.yml
                - Revert maximum-pool-size
                UNCERTAINTIES:
                - Check worker concurrency
                """;
        BifrostResponse mockAiResponse = new BifrostResponse(
                "chatcmpl-mock-001", "chat.completion", 12345, "gpt-4o",
                List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(mockAiText), "stop")),
                new BifrostResponse.Usage(100, 50, 150)
        );
        when(bifrostClient.chatCompletion(any())).thenReturn(mockAiResponse);

        // Mock Tavily Technical Research
        when(tavilyService.isAvailable()).thenReturn(true);
        ExternalResearchFinding mockFinding = ExternalResearchFinding.create(
                "HikariCP connection pool timeout",
                "HikariCP Troubleshooting Guide",
                "https://github.com/brettwooldridge/HikariCP/wiki",
                "When connection pool is exhausted, threads wait until timeout.",
                0.92
        );
        when(tavilyService.research(anyString(), anyString())).thenReturn(List.of(mockFinding));

        String logContent = """
                2026-10-10 14:15:00.000 ERROR 1 --- [HikariPool-1] org.postgresql.Driver : java.sql.SQLTimeoutException: Connection timeout after 30000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                """;

        String escapedRepoPath = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "%s",
                    "serviceName": "order-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepoPath, logContent.replace("\n", "\\n").replace("\"", "\\\""));

        // Step 1: POST /api/investigations/agent
        MvcResult result = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").exists())
                .andExpect(jsonPath("$.investigationId").exists())
                .andExpect(jsonPath("$.summary").exists())
                .andExpect(jsonPath("$.finalStatus").exists())
                .andExpect(jsonPath("$.externalResearchFindings", hasSize(1)))
                .andExpect(jsonPath("$.investigationSteps", hasSize(5)))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString());
        String investigationId = jsonNode.get("investigationId").asText();

        // Step 2: GET /api/investigations/{id}/agent-report
        mockMvc.perform(get("/api/investigations/" + investigationId + "/agent-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationId").value(investigationId))
                .andExpect(jsonPath("$.summary").exists());

        // Step 3: GET /api/investigations/{id}/research
        mockMvc.perform(get("/api/investigations/" + investigationId + "/research"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("HikariCP Troubleshooting Guide"))
                .andExpect(jsonPath("$[0].url").value("https://github.com/brettwooldridge/HikariCP/wiki"));

        // Step 4: GET /api/investigations/{id}/agent-steps
        mockMvc.perform(get("/api/investigations/" + investigationId + "/agent-steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].stepName").value("Deterministic Ingestion"))
                .andExpect(jsonPath("$[1].stepName").value("External Technical Research"))
                .andExpect(jsonPath("$[2].stepName").value("Bifrost AI Gateway Reasoning"))
                .andExpect(jsonPath("$[3].stepName").value("Evidence Grounding & Claim Validation"))
                .andExpect(jsonPath("$[4].stepName").value("Final Verdict"));

        // Step 5: Verify route aliases resolve correctly
        mockMvc.perform(get("/api/investigations/agent/" + investigationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationId").value(investigationId));

        mockMvc.perform(get("/api/investigations/agent/" + investigationId + "/research"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/investigations/agent/" + investigationId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    @DisplayName("Should return 404 Not Found for non-existent agent investigation report")
    void shouldReturn404ForNonExistentReport() throws Exception {
        UUID nonExistent = UUID.randomUUID();
        mockMvc.perform(get("/api/investigations/" + nonExistent + "/agent-report"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/investigations/" + nonExistent + "/research"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/investigations/" + nonExistent + "/agent-steps"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Agent API: WORKING_TREE_FORENSICS mode accepts empty telemetry and identifies missing tracked files")
    void shouldExecuteAgentWorkingTreeForensicsWithoutLogsOrStackTrace(@TempDir Path tempRepo) throws Exception {
        // Setup git repo with tracked file that physically disappears from working directory
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file1 = tempRepo.resolve("BackendService.java");
            Files.writeString(file1, "public class BackendService {}");
            git.add().addFilepattern("BackendService.java").call();
            git.commit().setMessage("Initial commit tracking backend").call();

            // Physically delete file from working tree without git rm/commit
            Files.delete(file1);
        }

        String payload = """
                {
                    "investigationMode": "WORKING_TREE_FORENSICS",
                    "repositoryPath": "%s",
                    "serviceName": "backend-service",
                    "environment": "staging"
                }
                """.formatted(tempRepo.toString().replace("\\", "\\\\"));

        MvcResult result = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").isNotEmpty())
                .andExpect(jsonPath("$.finalStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("Working tree anomaly: 1 tracked files missing from disk")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Unknown error"))))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Runtime failure"))))
                .andExpect(jsonPath("$.primarySuspectedRootCause").value(org.hamcrest.Matchers.containsString("Working tree filesystem anomaly")))
                .andExpect(jsonPath("$.supportingEvidenceIds[0]").value(org.hamcrest.Matchers.startsWith("EVD-WT-MISSING-")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("OneDrive caused");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Antivirus quarantined");
    }

    @Test
    @DisplayName("Phase 2.50: POST /api/investigations/agent deduplicates identical implicit requests")
    void shouldDeduplicateIdenticalAgentInvestigationRequests(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("Worker.java");
            Files.writeString(file, "class Worker {}");
            git.add().addFilepattern("Worker.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        when(bifrostClient.isAvailable()).thenReturn(false);
        when(tavilyService.isAvailable()).thenReturn(false);

        String escapedRepo = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "2026-10-10 15:00:00.000 ERROR 1 --- [worker] Worker : IllegalStateException in Worker.java:5",
                    "serviceName": "worker-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepo);

        // 1. Initial agent request: not deduplicated
        MvcResult res1 = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "false"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-TraceBack-Investigation-Id"))
                .andReturn();

        String invId1 = res1.getResponse().getHeader("X-TraceBack-Investigation-Id");

        // 2. Identical agent request: deduplicated
        MvcResult res2 = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Investigation-Id", invId1))
                .andReturn();

        // 3. Request with forceNew=true: fresh execution
        String forceNewPayload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "2026-10-10 15:00:00.000 ERROR 1 --- [worker] Worker : IllegalStateException in Worker.java:5",
                    "serviceName": "worker-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10,
                    "forceNew": true
                }
                """.formatted(escapedRepo);

        MvcResult res3 = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forceNewPayload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "false"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-TraceBack-Investigation-Id"))
                .andReturn();

        String invId3 = res3.getResponse().getHeader("X-TraceBack-Investigation-Id");
        org.assertj.core.api.Assertions.assertThat(invId3).isNotEqualTo(invId1);
    }

    @Test
    @DisplayName("POST /api/investigations/agent/run route alias executes agent investigation and supports deduplication")
    void testAgentRunEndpointAlias(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("Server.java");
            Files.writeString(file, "class Server {}");
            git.add().addFilepattern("Server.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        when(bifrostClient.isAvailable()).thenReturn(false);
        when(tavilyService.isAvailable()).thenReturn(false);

        String escapedRepo = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "2026-10-10 17:00:00.000 ERROR 1 --- [server] Server : SocketTimeoutException in Server.java:10",
                    "serviceName": "server-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepo);

        // First call to /api/investigations/agent/run
        MvcResult res1 = mockMvc.perform(post("/api/investigations/agent/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "false"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-TraceBack-Investigation-Id"))
                .andReturn();

        String invId1 = res1.getResponse().getHeader("X-TraceBack-Investigation-Id");

        // Second call with identical payload: deduplicated
        mockMvc.perform(post("/api/investigations/agent/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Investigation-Id", invId1));
    }

    @Test
    @DisplayName("Continuous MDC correlation: thread local MDC does not leak after execution and maintains correlation")
    void testContinuousMdcCorrelationAcrossDeduplicatedExecution(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("Worker.java");
            Files.writeString(file, "class Worker {}");
            git.add().addFilepattern("Worker.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        when(bifrostClient.isAvailable()).thenReturn(false);
        when(tavilyService.isAvailable()).thenReturn(false);

        String escapedRepo = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "2026-10-10 18:00:00.000 ERROR 1 --- [worker] Worker : IllegalStateException in Worker.java:20",
                    "serviceName": "worker-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepo);

        // Before request, MDC is clean
        org.assertj.core.api.Assertions.assertThat(com.traceback.core.logging.InvestigationLoggingContext.get()).isNull();

        MvcResult res1 = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "false"))
                .andReturn();

        String invId = res1.getResponse().getHeader("X-TraceBack-Investigation-Id");
        org.assertj.core.api.Assertions.assertThat(invId).isNotNull();

        // After completion of initial request, MDC must NOT leak into the caller thread
        org.assertj.core.api.Assertions.assertThat(com.traceback.core.logging.InvestigationLoggingContext.get()).isNull();

        // Second request (deduplicated cache hit):
        MvcResult res2 = mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Deduplicated", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-TraceBack-Investigation-Id", invId))
                .andReturn();

        // After completion of deduplicated request, MDC must NOT leak
        org.assertj.core.api.Assertions.assertThat(com.traceback.core.logging.InvestigationLoggingContext.get()).isNull();

        // Querying endpoints with ID also correctly cleans up MDC
        mockMvc.perform(get("/api/investigations/" + invId + "/agent-report"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(com.traceback.core.logging.InvestigationLoggingContext.get()).isNull();
    }
}
