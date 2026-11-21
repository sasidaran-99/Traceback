package com.traceback.api.controller;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
@WithMockUser(roles = "OPERATOR")
class InvestigationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("End-to-End API Flow: POST /api/investigations and verify timeline, evidence, hypotheses endpoints")
    void shouldExecuteEndToEndInvestigationWorkflow(@TempDir Path tempRepo) throws Exception {
        // 1. Create a minimal valid Git repository
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        org.eclipse.jgit.lib.PersonIdent aliceIdent = new org.eclipse.jgit.lib.PersonIdent("Alice", "alice@example.com",
                java.util.Date.from(java.time.Instant.parse("2026-10-10T14:10:00Z")), utc);

        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("application.properties");
            Files.writeString(file, "spring.datasource.hikari.connection-timeout=3000\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Reduce timeout to 3s")
                    .setAuthor(aliceIdent).setCommitter(aliceIdent).call();
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
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].confidenceBreakdown").exists())
                .andExpect(jsonPath("$[0].evidenceIds").isArray());

        // GET /api/investigations/{id}/claims -> 200 OK
        mockMvc.perform(get(location + "/claims"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].claimId").isNotEmpty())
                .andExpect(jsonPath("$[0].status").value("SUPPORTED"));
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
    @DisplayName("POST /api/investigations should reject invalid non-existent path within authorized boundary with 400 Bad Request")
    void shouldRejectNonExistentPath(@TempDir Path tempDir) throws Exception {
        String nonExistentPath = tempDir.resolve("non_existent_folder_xyz_123").toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "serviceName": "payment-service"
                }
                """.formatted(nonExistentPath);

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("does not exist")));
    }

    @Test
    @DisplayName("POST /api/investigations should reject unauthorized repository path with 403 Forbidden")
    void shouldRejectUnauthorizedPath() throws Exception {
        String payload = """
                {
                    "repositoryPath": "C:\\\\Windows\\\\System32\\\\unauthorized_repo",
                    "serviceName": "payment-service"
                }
                """;

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(containsString("Access denied")));
    }

    @Test
    @DisplayName("GET /api/investigations/{id} should return 404 for unknown ID")
    void shouldReturn404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/investigations/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/investigations/analyze alias should also initiate deterministic investigation")
    void shouldSupportAnalyzeAlias(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("Test.java");
            Files.writeString(file, "class Test {}");
            git.add().addFilepattern("Test.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        String payload = """
                {
                    "repositoryPath": "%s",
                    "serviceName": "order-service",
                    "environment": "production",
                    "stackTrace": "java.lang.NullPointerException\\n\\tat com.example.Test.run(Test.java:10)"
                }
                """.formatted(tempRepo.toString().replace("\\", "\\\\"));

        mockMvc.perform(post("/api/investigations/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.investigationId").isNotEmpty())
                .andExpect(jsonPath("$.serviceName").value("order-service"));
    }

    @Test
    @DisplayName("POST /api/investigations with WORKING_TREE_FORENSICS accepts empty logs and stack trace")
    void shouldAcceptEmptyLogsAndStackTraceInWorkingTreeForensicsMode(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("App.java");
            Files.writeString(file, "class App {}");
            git.add().addFilepattern("App.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        String payload = """
                {
                    "investigationMode": "WORKING_TREE_FORENSICS",
                    "repositoryPath": "%s",
                    "serviceName": "forensics-service",
                    "environment": "production"
                }
                """.formatted(tempRepo.toString().replace("\\", "\\\\"));

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.investigationId").isNotEmpty())
                .andExpect(jsonPath("$.investigationMode").value("WORKING_TREE_FORENSICS"))
                .andExpect(jsonPath("$.serviceName").value("forensics-service"));
    }

    @Test
    @DisplayName("POST /api/investigations with RUNTIME_FAILURE rejects empty logs and stack trace with 400 Bad Request")
    void shouldRejectEmptyLogsAndStackTraceInExplicitRuntimeFailureMode(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("App.java");
            Files.writeString(file, "class App {}");
            git.add().addFilepattern("App.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        String payload = """
                {
                    "investigationMode": "RUNTIME_FAILURE",
                    "repositoryPath": "%s",
                    "serviceName": "payment-service",
                    "environment": "production"
                }
                """.formatted(tempRepo.toString().replace("\\", "\\\\"));

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Please provide either log content or a stack trace for runtime failure investigations.")));
    }

    @Test
    @DisplayName("POST /api/investigations with WORKING_TREE_FORENSICS rejects blank serviceName with 400 Bad Request")
    void shouldRejectBlankServiceNameInWorkingTreeForensicsMode(@TempDir Path tempRepo) throws Exception {
        String payload = """
                {
                    "investigationMode": "WORKING_TREE_FORENSICS",
                    "repositoryPath": "%s",
                    "serviceName": "   ",
                    "environment": "production"
                }
                """.formatted(tempRepo.toString().replace("\\", "\\\\"));

        mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("serviceName cannot be blank in WORKING_TREE_FORENSICS mode")));
    }

    @Test
    @DisplayName("Phase 2.50: POST /api/investigations deduplication returns matching investigation ID and headers")
    void shouldDeduplicateIdenticalInvestigationRequests(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("Main.java");
            Files.writeString(file, "class Main { void run() {} }");
            git.add().addFilepattern("Main.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        String logContent = "2026-10-10 12:00:00.000 ERROR 1 --- [main] App : NullPointerException in Main.java:10";
        String escapedRepo = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "%s",
                    "serviceName": "dedup-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepo, logContent);

        // 1. Initial request: not deduplicated
        MvcResult res1 = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-TraceBack-Deduplicated", "false"))
                .andExpect(header().exists("X-TraceBack-Investigation-Id"))
                .andReturn();

        String id1 = res1.getResponse().getHeader("X-TraceBack-Investigation-Id");

        // 2. Identical request: deduplicated
        MvcResult res2 = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-TraceBack-Deduplicated", "true"))
                .andExpect(header().string("X-TraceBack-Investigation-Id", id1))
                .andReturn();

        // 3. Request with forceNew=true: bypasses deduplication, creates new ID
        String forceNewPayload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "%s",
                    "serviceName": "dedup-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10,
                    "forceNew": true
                }
                """.formatted(escapedRepo, logContent);

        MvcResult res3 = mockMvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forceNewPayload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-TraceBack-Deduplicated", "false"))
                .andExpect(header().exists("X-TraceBack-Investigation-Id"))
                .andReturn();

        String id3 = res3.getResponse().getHeader("X-TraceBack-Investigation-Id");
        org.assertj.core.api.Assertions.assertThat(id3).isNotEqualTo(id1);
    }

    @Test
    @DisplayName("POST /api/investigations/analyze route alias executes investigation and supports deduplication")
    void testAnalyzeEndpointAlias(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path file = tempRepo.resolve("App.java");
            Files.writeString(file, "class App {}");
            git.add().addFilepattern("App.java").call();
            git.commit().setMessage("Initial commit").call();
        }

        String escapedRepo = tempRepo.toString().replace("\\", "\\\\");
        String payload = """
                {
                    "repositoryPath": "%s",
                    "logContent": "2026-10-10 16:00:00.000 ERROR 1 --- [main] App : NullPointerException",
                    "serviceName": "alias-service",
                    "environment": "production",
                    "windowMinutes": 60,
                    "maxCommits": 10
                }
                """.formatted(escapedRepo);

        // First call to /api/investigations/analyze
        MvcResult res1 = mockMvc.perform(post("/api/investigations/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-TraceBack-Deduplicated", "false"))
                .andExpect(header().exists("X-TraceBack-Investigation-Id"))
                .andReturn();

        String invId1 = res1.getResponse().getHeader("X-TraceBack-Investigation-Id");

        // Second call with identical payload: deduplicated
        mockMvc.perform(post("/api/investigations/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-TraceBack-Deduplicated", "true"))
                .andExpect(header().string("X-TraceBack-Investigation-Id", invId1));
    }

    @Test
    @DisplayName("Follower wait timeout returns HTTP 504 Gateway Timeout with Retry-After: 5 header")
    void testFollowerTimeoutReturnsHttp504WithRetryAfterHeader() throws Exception {
        // Test GlobalExceptionHandler mapping of DeduplicationWaitTimeoutException
        String testUri = "/api/investigations";
        com.traceback.core.investigation.DeduplicationWaitTimeoutException ex =
                new com.traceback.core.investigation.DeduplicationWaitTimeoutException("fp-test-timeout", 45L);

        com.traceback.api.exception.GlobalExceptionHandler handler = new com.traceback.api.exception.GlobalExceptionHandler();
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRequestURI(testUri);

        org.springframework.http.ResponseEntity<com.traceback.api.dto.ApiErrorResponse> response =
                handler.handleDeduplicationWaitTimeout(ex, request);

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(504);
        org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        org.assertj.core.api.Assertions.assertThat(response.getBody()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(response.getBody().status()).isEqualTo(504);
        org.assertj.core.api.Assertions.assertThat(response.getBody().error()).isEqualTo("Gateway Timeout");
        org.assertj.core.api.Assertions.assertThat(response.getBody().message()).contains("fp-test-timeout");
    }
}
