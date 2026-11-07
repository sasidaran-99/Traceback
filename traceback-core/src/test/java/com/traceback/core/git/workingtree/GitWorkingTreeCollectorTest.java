package com.traceback.core.git.workingtree;

import com.traceback.core.git.GitCollector;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWorkingTreeCollectorTest {

    @Test
    @DisplayName("Should detect clean working tree when no uncommitted changes exist")
    void shouldDetectCleanWorkingTree(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path file = tempDir.resolve("README.md");
            Files.writeString(file, "# Clean Project\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").setSign(false).call();

            GitWorkingTreeCollector collector = new GitWorkingTreeCollector(
                    new GitCollector(List.of(tempDir.toString())),
                    List.of(tempDir.toString())
            );

            WorkingTreeSnapshot snapshot = collector.collectWorkingTree(tempDir.toString());
            assertThat(snapshot.hasChanges()).isFalse();
            assertThat(snapshot.hasMissingTrackedFiles()).isFalse();
            assertThat(snapshot.missingTrackedCount()).isEqualTo(0);
            assertThat(snapshot.changes()).isEmpty();
            assertThat(snapshot.generateSummary()).contains("clean");
        }
    }

    @Test
    @DisplayName("Should detect missing tracked files when tracked files are physically absent from disk")
    void shouldDetectMissingTrackedFiles(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path backendDir = tempDir.resolve("backend/src/main/java/com/example");
            Files.createDirectories(backendDir);
            Path fileA = backendDir.resolve("OrderService.java");
            Path fileB = backendDir.resolve("PaymentService.java");

            Files.writeString(fileA, "public class OrderService {}");
            Files.writeString(fileB, "public class PaymentService {}");

            git.add().addFilepattern(".").call();
            var commit = git.commit().setMessage("Add services").setSign(false).call();
            String headSha = commit.getName();

            // Physically delete PaymentService.java without git commit or staging
            Files.delete(fileB);
            assertThat(Files.exists(fileB)).isFalse();

            GitWorkingTreeCollector collector = new GitWorkingTreeCollector(
                    new GitCollector(List.of(tempDir.toString())),
                    List.of(tempDir.toString())
            );

            WorkingTreeSnapshot snapshot = collector.collectWorkingTree(tempDir.toString());
            assertThat(snapshot.hasChanges()).isTrue();
            assertThat(snapshot.hasMissingTrackedFiles()).isTrue();
            assertThat(snapshot.missingTrackedCount()).isEqualTo(1);
            assertThat(snapshot.headCommitSha()).isEqualTo(headSha);

            WorkingTreeEvidence missing = snapshot.getMissingFiles().get(0);
            assertThat(missing.relativePath()).isEqualTo("backend/src/main/java/com/example/PaymentService.java");
            assertThat(missing.changeType()).isEqualTo(WorkingTreeChangeType.MISSING);
            assertThat(missing.stagingState()).isEqualTo(WorkingTreeStagingState.UNSTAGED);
            assertThat(missing.expectedInHead()).isTrue();
            assertThat(missing.physicallyExists()).isFalse();
            assertThat(missing.affectedModule()).isEqualTo("backend/main");
            assertThat(missing.evidenceId()).startsWith("EVD-WT-MISSING-");
            assertThat(missing.source()).isEqualTo("WORKING_TREE");
            assertThat(snapshot.generateSummary()).contains("1 tracked file(s) missing from disk");
        }
    }

    @Test
    @DisplayName("Should detect modified tracked files on disk")
    void shouldDetectModifiedFiles(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path file = tempDir.resolve("config.properties");
            Files.writeString(file, "timeout=5000\n");
            git.add().addFilepattern("config.properties").call();
            git.commit().setMessage("Initial config").setSign(false).call();

            // Modify file on disk without commit
            Files.writeString(file, "timeout=1000\n");

            GitWorkingTreeCollector collector = new GitWorkingTreeCollector(
                    new GitCollector(List.of(tempDir.toString())),
                    List.of(tempDir.toString())
            );

            WorkingTreeSnapshot snapshot = collector.collectWorkingTree(tempDir.toString());
            assertThat(snapshot.hasChanges()).isTrue();
            assertThat(snapshot.getModifiedFiles()).hasSize(1);
            WorkingTreeEvidence mod = snapshot.getModifiedFiles().get(0);
            assertThat(mod.relativePath()).isEqualTo("config.properties");
            assertThat(mod.changeType()).isEqualTo(WorkingTreeChangeType.MODIFIED);
            assertThat(mod.stagingState()).isEqualTo(WorkingTreeStagingState.UNSTAGED);
            assertThat(mod.physicallyExists()).isTrue();
        }
    }

    @Test
    @DisplayName("Should detect untracked new files on disk")
    void shouldDetectUntrackedFiles(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path file = tempDir.resolve("README.md");
            Files.writeString(file, "# Repo\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial").setSign(false).call();

            // Create untracked file
            Path untracked = tempDir.resolve("scratch.txt");
            Files.writeString(untracked, "temp notes");

            GitWorkingTreeCollector collector = new GitWorkingTreeCollector(
                    new GitCollector(List.of(tempDir.toString())),
                    List.of(tempDir.toString())
            );

            WorkingTreeSnapshot snapshot = collector.collectWorkingTree(tempDir.toString());
            assertThat(snapshot.getUntrackedFiles()).hasSize(1);
            WorkingTreeEvidence ev = snapshot.getUntrackedFiles().get(0);
            assertThat(ev.relativePath()).isEqualTo("scratch.txt");
            assertThat(ev.changeType()).isEqualTo(WorkingTreeChangeType.UNTRACKED);
            assertThat(ev.stagingState()).isEqualTo(WorkingTreeStagingState.UNTRACKED);
        }
    }

    @Test
    @DisplayName("Should group missing files by architectural module and file extension")
    void shouldGroupMissingByModuleAndExtension(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path mainDir = tempDir.resolve("backend/src/main/java/com/example");
            Path migDir = tempDir.resolve("backend/src/main/resources/db/migration");
            Path testDir = tempDir.resolve("backend/src/test/java/com/example");
            Files.createDirectories(mainDir);
            Files.createDirectories(migDir);
            Files.createDirectories(testDir);

            Path f1 = mainDir.resolve("ServiceA.java");
            Path f2 = mainDir.resolve("ServiceB.java");
            Path f3 = migDir.resolve("V1__init.sql");
            Path f4 = testDir.resolve("ServiceTest.java");

            Files.writeString(f1, "class ServiceA {}");
            Files.writeString(f2, "class ServiceB {}");
            Files.writeString(f3, "CREATE TABLE users;");
            Files.writeString(f4, "class ServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add files").setSign(false).call();

            // Physically delete 3 of them
            Files.delete(f1);
            Files.delete(f3);
            Files.delete(f4);

            GitWorkingTreeCollector collector = new GitWorkingTreeCollector(
                    new GitCollector(List.of(tempDir.toString())),
                    List.of(tempDir.toString())
            );

            WorkingTreeSnapshot snapshot = collector.collectWorkingTree(tempDir.toString());
            assertThat(snapshot.missingTrackedCount()).isEqualTo(3);

            Map<String, List<WorkingTreeEvidence>> moduleMap = snapshot.groupByAffectedModule();
            assertThat(moduleMap).containsKey("backend/main");
            assertThat(moduleMap).containsKey("backend/db/migration");
            assertThat(moduleMap).containsKey("backend/test");

            Map<String, Long> extCounts = snapshot.countMissingByExtension();
            assertThat(extCounts.get(".java")).isEqualTo(2L);
            assertThat(extCounts.get(".sql")).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("Should enforce security boundary and reject unauthorized repositories")
    void shouldEnforceSecurityBoundary(@TempDir Path baseDir) throws Exception {
        Path authorized = baseDir.resolve("authorized_zone");
        Path unauthorized = baseDir.resolve("unauthorized_zone");
        Files.createDirectories(authorized);
        Files.createDirectories(unauthorized);

        try (Git gitA = Git.init().setDirectory(authorized.toFile()).call();
             Git gitB = Git.init().setDirectory(unauthorized.toFile()).call()) {

            GitWorkingTreeCollector collector = new GitWorkingTreeCollector(
                    new GitCollector(List.of(authorized.toString())),
                    List.of(authorized.toString())
            );

            // Authorized succeeds
            WorkingTreeSnapshot s = collector.collectWorkingTree(authorized.toString());
            assertThat(s).isNotNull();

            // Unauthorized throws SecurityException
            assertThatThrownBy(() -> collector.collectWorkingTree(unauthorized.toString()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied: Repository path is not within the authorized repository roots");
        }
    }
}
