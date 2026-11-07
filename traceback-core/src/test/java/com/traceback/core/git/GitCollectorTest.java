package com.traceback.core.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCollectorTest {

    private final GitCollector collector = new GitCollector();

    @Test
    @DisplayName("Should validate valid Git repository path and reject non-existent or invalid paths")
    void shouldValidateRepositoryPaths(@TempDir Path tempDir) throws Exception {
        // Non-existent directory
        assertThatThrownBy(() -> collector.validateRepositoryPath(tempDir.resolve("non_existent").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist or is inaccessible");

        // Directory without .git
        assertThatThrownBy(() -> collector.validateRepositoryPath(tempDir.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing .git directory");

        // Valid Git repository
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path validPath = collector.validateRepositoryPath(tempDir.toString());
            assertThat(validPath).isEqualTo(tempDir.toRealPath());
        }
    }

    @Test
    @DisplayName("Should enforce strict repository root security boundary and reject unauthorized paths")
    void shouldEnforceSecurityBoundary(@TempDir Path baseDir) throws Exception {
        Path authorizedRoot = baseDir.resolve("authorized_zone");
        Path unauthorizedRoot = baseDir.resolve("unauthorized_zone");
        Files.createDirectories(authorizedRoot);
        Files.createDirectories(unauthorizedRoot);

        // Git repo inside authorized zone
        Path authorizedRepo = authorizedRoot.resolve("service-repo");
        Files.createDirectories(authorizedRepo);
        try (Git git = Git.init().setDirectory(authorizedRepo.toFile()).call()) {
            // empty
        }

        // Git repo inside unauthorized zone
        Path unauthorizedRepo = unauthorizedRoot.resolve("sensitive-repo");
        Files.createDirectories(unauthorizedRepo);
        try (Git git = Git.init().setDirectory(unauthorizedRepo.toFile()).call()) {
            // empty
        }

        // Configure collector with ONLY authorizedRoot
        GitCollector securedCollector = new GitCollector(List.of(authorizedRoot.toString()));

        // Authorized repo succeeds
        Path validated = securedCollector.validateRepositoryPath(authorizedRepo.toString());
        assertThat(validated).isEqualTo(authorizedRepo.toRealPath());

        // Unauthorized repo throws SecurityException without leaking sensitive details
        assertThatThrownBy(() -> securedCollector.validateRepositoryPath(unauthorizedRepo.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied: Repository path is not within the authorized repository roots");

        // Directory traversal attempt (e.g. authorized_zone/../unauthorized_zone/sensitive-repo)
        String traversalPath = authorizedRoot.toString() + "/../unauthorized_zone/sensitive-repo";
        assertThatThrownBy(() -> securedCollector.validateRepositoryPath(traversalPath))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied: Repository path is not within the authorized repository roots");
    }

    @Test
    @DisplayName("Should collect commits, diffs, line counts, and configuration changes from real Git repo")
    void shouldCollectCommitsAndDiffs(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Commit 1: Add application.properties
            Path configFile = tempDir.resolve("application.properties");
            Files.writeString(configFile, "spring.datasource.hikari.connection-timeout=30000\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Initial database config").setAuthor("Alice", "alice@example.com").call();

            // Commit 2: Modify application.properties
            Files.writeString(configFile, "spring.datasource.hikari.connection-timeout=3000\n");
            git.add().addFilepattern("application.properties").call();
            git.commit().setMessage("Reduce database timeout to 3s").setAuthor("Bob", "bob@example.com").call();

            // Commit 3: Add Java file
            Path javaFile = tempDir.resolve("PaymentService.java");
            Files.writeString(javaFile, "public class PaymentService {}\n");
            git.add().addFilepattern("PaymentService.java").call();
            git.commit().setMessage("Add payment service skeleton").setAuthor("Carol", "carol@example.com").call();

            List<CommitEvidence> commits = collector.collectCommits(tempDir.toString(), 10);

            assertThat(commits).hasSize(3);

            // Newest first
            CommitEvidence c3 = commits.get(0);
            assertThat(c3.commitMessage()).isEqualTo("Add payment service skeleton");
            assertThat(c3.hasCodeChanges()).isTrue();
            assertThat(c3.touchesFile("PaymentService.java")).isTrue();

            CommitEvidence c2 = commits.get(1);
            assertThat(c2.commitMessage()).isEqualTo("Reduce database timeout to 3s");
            assertThat(c2.hasConfigurationChanges()).isTrue();
            assertThat(c2.changedFiles()).hasSize(1);

            ChangedFile configChange = c2.changedFiles().get(0);
            assertThat(configChange.isConfigFile()).isTrue();
            assertThat(configChange.configChanges()).containsKey("spring.datasource.hikari.connection-timeout");
            assertThat(configChange.configChanges().get("spring.datasource.hikari.connection-timeout"))
                    .contains("30000 => 3000");

            CommitEvidence c1 = commits.get(2);
            assertThat(c1.commitMessage()).isEqualTo("Initial database config");
        }
    }
}
