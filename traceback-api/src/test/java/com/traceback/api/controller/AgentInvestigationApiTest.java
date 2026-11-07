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
}
