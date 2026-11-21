package com.traceback.core.investigation;

import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.GitCollector;
import com.traceback.core.git.workingtree.GitWorkingTreeCollector;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.validation.DeterministicClaimValidator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossProjectGeneralizationTest {

    private final TimeZone utc = TimeZone.getTimeZone("UTC");
    private final PersonIdent devIdent = new PersonIdent("Developer", "dev@universal.io",
            Date.from(Instant.parse("2026-11-08T10:00:00Z")), utc);

    private ConcreteEvidenceRegistry evidenceRegistry;
    private InvestigationService investigationService;
    private InvestigationAgent agent;

    @BeforeEach
    void setUp() {
        evidenceRegistry = new ConcreteEvidenceRegistry();
        investigationService = new InvestigationService(
                new GitCollector(),
                new StackTraceParser(),
                new LogAnalyzer(),
                new TimelineBuilder(),
                new CorrelationEngine(),
                evidenceRegistry
        );

        BifrostClient mockBifrost = mock(BifrostClient.class);
        when(mockBifrost.isAvailable()).thenReturn(false);
        TavilyResearchService mockTavily = mock(TavilyResearchService.class);
        when(mockTavily.isAvailable()).thenReturn(false);

        agent = new InvestigationAgent(
                mockBifrost,
                mockTavily,
                new DeterministicClaimValidator(),
                evidenceRegistry,
                investigationService
        );
    }

    @Test
    @DisplayName("Scenario 1: Java + Spring Boot failure scenario with config timeout reduction")
    void shouldInvestigateJavaSpringBootFailure(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path props = tempRepo.resolve("application.properties");
            Files.writeString(props, "spring.datasource.hikari.connection-timeout=3000\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Reduce database connection timeout to 3s").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        String logContent = """
                2026-11-08 10:15:00.100 ERROR 1 --- [main] c.z.h.pool.HikariPool : HikariPool-1 - Connection is not available, request timed out after 3000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                """;

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, logContent, "java-service", "production", 60, 10
        );

        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis top = result.hypotheses().getFirst();
        assertThat(top.getSummary()).contains("Reduce database connection timeout to 3s");

        InvestigationAgentReport report = agent.investigate(result);
        assertThat(report.finalStatus()).isIn(AgentInvestigationStatus.CONFIRMED, AgentInvestigationStatus.PROBABLE);
    }

    @Test
    @DisplayName("Scenario 2: Python + Django crash scenario with views.py code overlap and OperationalError")
    void shouldInvestigatePythonDjangoFailure(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path viewsFile = tempRepo.resolve("views.py");
            Files.writeString(viewsFile, """
                    import psycopg2
                    def get_active_users():
                        conn = psycopg2.connect("dbname=users user=postgres host=localhost port=5432")
                        return conn.cursor().execute("SELECT * FROM users")
                    """);
            git.add().addFilepattern("views.py").call();
            git.commit().setMessage("refactor: update user querying in views.py").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        String pythonTrace = """
                2026-11-08 10:15:00,123 - root - ERROR - database connection error
                Traceback (most recent call last):
                  File "/app/views.py", line 4, in get_active_users
                    conn = psycopg2.connect("dbname=users user=postgres host=localhost port=5432")
                OperationalError: connection to server at "localhost", port 5432 failed: Connection refused
                """;

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, pythonTrace, "django-backend", "production", 60, 10
        );

        assertThat(result.primaryException()).isNotNull();
        assertThat(result.primaryException().exceptionType()).isEqualTo("OperationalError");
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis top = result.hypotheses().getFirst();
        assertThat(top.getSummary()).contains("views.py");
        assertThat(top.getMatchedRules()).contains("RULE_B_STACK_TRACE_OVERLAP");

        InvestigationAgentReport report = agent.investigate(result);
        assertThat(report.finalStatus()).isIn(AgentInvestigationStatus.CONFIRMED, AgentInvestigationStatus.PROBABLE);
    }

    @Test
    @DisplayName("Scenario 3: Node.js + Express unhandled error scenario with server.js frame overlap")
    void shouldInvestigateNodeJsExpressFailure(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path serverFile = tempRepo.resolve("server.js");
            Files.writeString(serverFile, """
                    const express = require('express');
                    const app = express();
                    app.get('/users', (req, res) => {
                        const users = null;
                        res.json(users.length);
                    });
                    """);
            git.add().addFilepattern("server.js").call();
            git.commit().setMessage("feat: add users endpoint in server.js").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        String nodeTrace = """
                [2026-11-08T10:15:00.000Z] [ERROR] [app] Unhandled exception in request handler
                TypeError: Cannot read properties of null (reading 'length')
                    at handleUsers (/app/server.js:5:24)
                    at Layer.handle [as handle_request] (/app/node_modules/express/lib/router/layer.js:95:5)
                """;

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, nodeTrace, "node-api", "production", 60, 10
        );

        assertThat(result.primaryException()).isNotNull();
        assertThat(result.primaryException().exceptionType()).isEqualTo("TypeError");
        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis top = result.hypotheses().getFirst();
        assertThat(top.getSummary()).contains("server.js");
        assertThat(top.getMatchedRules()).contains("RULE_B_STACK_TRACE_OVERLAP");
    }

    @Test
    @DisplayName("Scenario 4: Generic Git repo in REPOSITORY_ONLY mode without logs")
    void shouldHandleRepositoryOnlyInvestigation(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path mainFile = tempRepo.resolve("main.go");
            Files.writeString(mainFile, "package main\nfunc main() {}\n");
            git.add().addFilepattern("main.go").call();
            git.commit().setMessage("chore: initial commit in Go").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, null, "go-service", "production", 60, 10,
                InvestigationMode.REPOSITORY_ONLY
        );

        assertThat(result.commitsAnalyzed()).isEqualTo(1);
        assertThat(result.errorsDetected()).isEqualTo(0);
        assertThat(result.evidenceSufficient()).isFalse();

        InvestigationAgentReport report = agent.investigate(result);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    @DisplayName("Scenario 5: Clean repository with unrelated error honestly yields INSUFFICIENT_EVIDENCE")
    void shouldAbstainWithInsufficientEvidenceForCleanRepoWithUnrelatedError(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path readme = tempRepo.resolve("README.md");
            Files.writeString(readme, "# Project Documentation\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("docs: update README documentation").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        String unrelatedLog = """
                2026-11-08 10:15:00.000 ERROR 1 --- [main] c.t.App : java.lang.SecurityException: Access denied to secret token
                    at java.lang.SecurityManager.checkPermission(SecurityManager.java:549)
                """;

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, unrelatedLog, "clean-service", "production", 60, 10
        );

        InvestigationAgentReport report = agent.investigate(result);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(report.summary()).contains("insufficient");
    }

    @Test
    @DisplayName("Scenario 6: Repository with missing tracked files triggers CONFIRMED working tree anomaly")
    void shouldDetectWorkingTreeAnomalyAcrossAnyRepository(@TempDir Path tempRepo) throws Exception {
        Path trackedFile = tempRepo.resolve("config.toml");
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Files.writeString(trackedFile, "[server]\nport = 8080\n");
            git.add().addFilepattern("config.toml").call();
            git.commit().setMessage("feat: add config.toml").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        // Delete tracked file from disk without Git commit
        Files.delete(trackedFile);

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, null, "toml-service", "production", 60, 10,
                InvestigationMode.WORKING_TREE_FORENSICS
        );

        assertThat(result.hypotheses()).isNotEmpty();
        Hypothesis anomaly = result.hypotheses().getFirst();
        assertThat(anomaly.getMatchedRules()).contains("RULE_WT_MISSING_TRACKED_FILES");

        InvestigationAgentReport report = agent.investigate(result);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.CONFIRMED);
        assertThat(report.primarySuspectedRootCause()).contains("Working tree filesystem anomaly");
    }

    @Test
    @DisplayName("Scenario 7: Repository with staged and unstaged changes accurately captured in snapshot")
    void shouldCaptureStagedAndUnstagedChangesInWorkingTree(@TempDir Path tempRepo) throws Exception {
        Path fileA = tempRepo.resolve("fileA.txt");
        Path fileB = tempRepo.resolve("fileB.txt");
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Files.writeString(fileA, "initial A\n");
            Files.writeString(fileB, "initial B\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial files").setAuthor(devIdent).setCommitter(devIdent).call();

            // Staged modification
            Files.writeString(fileA, "staged modification in A\n");
            git.add().addFilepattern("fileA.txt").call();

            // Unstaged modification
            Files.writeString(fileB, "unstaged modification in B\n");
        }

        GitWorkingTreeCollector wtCollector = new GitWorkingTreeCollector();
        WorkingTreeSnapshot snapshot = wtCollector.collectWorkingTree(tempRepo.toString());

        assertThat(snapshot.changes()).hasSize(2);
        assertThat(snapshot.getStagedFiles()).hasSize(1);
        assertThat(snapshot.getModifiedFiles()).hasSize(1);
        assertThat(snapshot.getMissingFiles()).isEmpty();
    }

    @Test
    @DisplayName("Scenario 8: Repository with sensitive configuration changes redacts secrets in evidence")
    void shouldRedactSensitiveSecretsInEvidenceAndDiffs(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path config = tempRepo.resolve("secrets.properties");
            Files.writeString(config, "database.password=fakeSecretPass123\nopenai.api_key=sk-mockFakeToken987654321012345\n");
            git.add().addFilepattern("secrets.properties").call();
            git.commit().setMessage("update production credentials").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, null, "sec-service", "production", 60, 10
        );

        for (EvidenceItem item : result.evidenceItems()) {
            assertThat(item.summary()).doesNotContain("fakeSecretPass123");
            assertThat(item.summary()).doesNotContain("sk-mockFakeToken987654321012345");
            if (item.metadata() != null) {
                assertThat(item.metadata().toString()).doesNotContain("fakeSecretPass123");
            }
        }
    }

    @Test
    @DisplayName("Scenario 9: Malformed telemetry degrades gracefully without crashing engine")
    void shouldDegradeGracefullyOnMalformedTelemetry(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path f = tempRepo.resolve("lib.rs");
            Files.writeString(f, "pub fn run() {}\n");
            git.add().addFilepattern("lib.rs").call();
            git.commit().setMessage("initial rust lib").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        String malformedGarbage = "\u0000\u0001\u0002??? [UNKNOWN CORRUPTED TELEMETRY STREAM] ??? %%% \n\n";

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, malformedGarbage, "rust-service", "production", 60, 10
        );

        assertThat(result).isNotNull();
        assertThat(result.commitsAnalyzed()).isEqualTo(1);
        assertThat(result.evidenceSufficient()).isFalse();
    }

    @Test
    @DisplayName("Scenario 10: Go or Rust repository investigation without logs builds timeline cleanly")
    void shouldInvestigateGoOrRustRepositoryWithoutLogs(@TempDir Path tempRepo) throws Exception {
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Path goMod = tempRepo.resolve("go.mod");
            Files.writeString(goMod, "module example.com/app\ngo 1.22\n");
            git.add().addFilepattern("go.mod").call();
            git.commit().setMessage("chore: init module").setAuthor(devIdent).setCommitter(devIdent).call();

            Path mainGo = tempRepo.resolve("main.go");
            Files.writeString(mainGo, "package main\nimport \"fmt\"\nfunc main() { fmt.Println(\"ok\") }\n");
            git.add().addFilepattern("main.go").call();
            git.commit().setMessage("feat: implement entrypoint").setAuthor(devIdent).setCommitter(devIdent).call();
        }

        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(), null, null, null, "go-app", "production", 60, 10
        );

        assertThat(result.commitsAnalyzed()).isEqualTo(2);
        assertThat(result.timeline()).hasSizeGreaterThanOrEqualTo(2);
    }
}
