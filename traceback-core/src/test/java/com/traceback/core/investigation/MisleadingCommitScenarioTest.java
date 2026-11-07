package com.traceback.core.investigation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
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

class MisleadingCommitScenarioTest {

    @Test
    @DisplayName("Main Demo Scenario: Rank older DB timeout reduction over misleading latest auth change")
    void shouldRankTimeoutChangeAboveMisleadingRecentAuthCommit(@TempDir Path tempRepo) throws Exception {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        PersonIdent devopsIdent = new PersonIdent("DevOps Engineer", "devops@example.com",
                Date.from(Instant.parse("2026-10-10T13:45:00Z")), utc);
        PersonIdent aliceIdent = new PersonIdent("Alice", "alice@example.com",
                Date.from(Instant.parse("2026-10-10T14:10:00Z")), utc);
        PersonIdent bobIdent = new PersonIdent("Bob", "bob@example.com",
                Date.from(Instant.parse("2026-10-10T14:14:00Z")), utc);

        // 1. Initialize real local Git repository
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {

            // Commit 1: Database connection timeout = 30 seconds
            Path configFile = tempRepo.resolve("application.properties");
            Files.writeString(configFile, "spring.datasource.hikari.connection-timeout=30000\nauth.login.message=Welcome\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Initial configuration with 30s connection timeout")
                    .setAuthor(devopsIdent).setCommitter(devopsIdent).call();

            // Commit 2: Database connection timeout reduced to 3 seconds
            Files.writeString(configFile, "spring.datasource.hikari.connection-timeout=3000\nauth.login.message=Welcome\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Tune database connection timeout to 3s")
                    .setAuthor(aliceIdent).setCommitter(aliceIdent).call();

            // Commit 3: Authentication log message renamed without changing database logic
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
            git.commit().setMessage("Rename authentication sign-in message in AuthService")
                    .setAuthor(bobIdent).setCommitter(bobIdent).call();
        }

        // 2. Prepare runtime logs with connection timeout failure
        String runtimeLogs = """
                2026-10-10 14:15:00.000 INFO 100 --- [main] c.t.PaymentApp : Service deployed and started successfully
                2026-10-10 14:16:35.100 ERROR 100 --- [HikariPool-1] org.postgresql.Driver : java.sql.SQLTimeoutException: Connection timeout after 3000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                2026-10-10 14:16:36.200 ERROR 100 --- [http-nio-exec-1] c.t.p.PaymentService : HikariPool-1 - Connection is not available, request timed out after 3000ms.
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                """;

        // 3. Execute deterministic investigation
        InvestigationService investigationService = new InvestigationService();
        InvestigationResult result = investigationService.startInvestigation(
                tempRepo.toString(),
                null, // no standalone stack trace; parsed from logs
                null,
                runtimeLogs,
                "payment-service",
                "production",
                60,
                10
        );

        // 4. Assertions on expected behavior
        // A. Identify all three commits
        assertThat(result.commitsAnalyzed()).isEqualTo(3);
        assertThat(result.timeline()).isNotEmpty();

        // B. Inspect their actual diffs and verify errors parsed
        assertThat(result.errorsDetected()).isEqualTo(2);
        assertThat(result.primaryException()).isNotNull();
        assertThat(result.primaryException().exceptionType()).isEqualTo("java.sql.SQLTimeoutException");

        // C. Match the timeout configuration change with the failure signature and rank Commit 2 above Commit 3
        List<Hypothesis> hypotheses = result.hypotheses();
        assertThat(hypotheses).hasSize(3);

        Hypothesis rank1 = hypotheses.get(0);
        assertThat(rank1.getRank()).isEqualTo(1);
        assertThat(rank1.getSummary()).contains("Tune database connection timeout to 3s");
        assertThat(rank1.getConfidenceScore()).isGreaterThanOrEqualTo(0.85);
        assertThat(rank1.getVerificationPlan()).contains("connection-timeout");

        // D. Explain why Commit 3 is demoted / not sufficiently related despite being the latest commit before failure
        Hypothesis authHypothesis = hypotheses.stream()
                .filter(h -> h.getSummary().contains("Rename authentication sign-in message"))
                .findFirst()
                .orElseThrow();
        assertThat(rank1.getRank()).isLessThan(authHypothesis.getRank());
        assertThat(authHypothesis.getConfidenceScore()).isLessThan(0.30);
        assertThat(authHypothesis.getCounterEvidence()).contains("AUTHENTICATION");
        assertThat(authHypothesis.getCounterEvidence()).contains("zero database or stack trace overlap");

        // E. Attach evidence IDs to all findings
        List<EvidenceItem> evidenceItems = result.evidenceItems();
        assertThat(evidenceItems).isNotEmpty();
        assertThat(evidenceItems).anyMatch(e -> e.evidenceId().startsWith("EVD-GIT-"));
        assertThat(evidenceItems).anyMatch(e -> e.evidenceId().startsWith("EVD-CFG-"));
        assertThat(evidenceItems).anyMatch(e -> e.evidenceId().startsWith("EVD-LOG-"));

        for (EvidenceItem item : evidenceItems) {
            assertThat(item.evidenceId()).isNotBlank();
            assertThat(item.integrityHash()).isNotBlank();
        }
    }
}
