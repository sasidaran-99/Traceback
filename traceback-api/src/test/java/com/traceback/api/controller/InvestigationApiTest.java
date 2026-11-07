package com.traceback.api.controller;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvestigationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("End-to-End API Flow: POST /api/investigations and verify timeline, evidence, hypotheses endpoints")
    void shouldExecuteEndToEndInvestigationWorkflow(@TempDir Path tempRepo) throws Exception {
        // 1. Create a minimal valid Git repository
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("application.properties");
            Files.writeString(file, "spring.datasource.hikari.connection-timeout=3000\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Reduce timeout to 3s").setAuthor("Alice", "alice@example.com").call();
        }

        String logContent = """
                2026-10-10 14:16:35.100 ERROR 100 --- [HikariPool-1] org.postgresql.Driver : java.sql.SQLTimeoutException: Connection timeout after 3000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                """;

        String escapedRepoPath = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "%s",
                    "serviceName": "payment-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepoPath, logContent.replace("\n", "\\n"));

        // POST /api/investigations -> 201 Created
        MvcResult result = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.investigationId").isNotEmpty())
                .andExpect(jsonPath("$.serviceName").value("payment-service"))
                .andExpect(jsonPath("$.commitsAnalyzed").value(1))
                .andExpect(jsonPath("$.errorsDetected").value(1))
                .andExpect(jsonPath("$.topHypothesis.rank").value(1))
                .andExpect(jsonPath("$.topHypothesis.confidenceScore").isNumber())
                .andReturn();

        String location = result.getResponse().getHeader("Location");

        // GET /api/investigations/{id} -> 200 OK
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // GET /api/investigations/{id}/timeline -> 200 OK
        mockMvc.perform(get(location + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].timestamp").isNotEmpty());

        // GET /api/investigations/{id}/evidence -> 200 OK
        mockMvc.perform(get(location + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].evidenceId").isNotEmpty())
                .andExpect(jsonPath("$[0].integrityHash").isNotEmpty());

        // GET /api/investigations/{id}/hypotheses -> 200 OK
        mockMvc.perform(get(location + "/hypotheses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].rank").value(1));
    }

    @Test
    @DisplayName("POST /api/investigations should reject blank repository path with 400 Bad Request")
    void shouldRejectBlankRepositoryPath() throws Exception {
        String payload = """
                {
                    "repositoryPath": "   ",
                    "serviceName": "payment-service"
                }
                """;

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("repositoryPath"));
    }

    @Test
    @DisplayName("POST /api/investigations should reject invalid non-existent path with 400 Bad Request")
    void shouldRejectNonExistentPath() throws Exception {
        String payload = """
                {
                    "repositoryPath": "C:\\\\non_existent_folder_xyz_123",
                    "serviceName": "payment-service"
                }
                """;

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("does not exist")));
    }

    @Test
    @DisplayName("GET /api/investigations/{id} should return 404 for unknown ID")
    void shouldReturn404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/investigations/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
