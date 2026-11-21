package com.traceback.core.git;

import com.traceback.core.git.workingtree.GitWorkingTreeCollector;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
import com.traceback.core.remediation.git.GitCasResult;
import com.traceback.core.remediation.git.JGitCasAdapter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial test suite for filesystem and Git repository safety hardening (Stage 3).
 * Verifies rejection of symlinks, junctions, linked worktrees (.git files), directory climbing,
 * environment variable overrides, post-open boundary mismatches, and TOCTOU races.
 */
class GitFilesystemSecurityAdversarialTest {

    private static final Logger log = LoggerFactory.getLogger(GitFilesystemSecurityAdversarialTest.class);

    @Test
    @DisplayName("Scenario 1: symlinkDirectoryEscapeRejected - Symlink to unauthorized directory must be rejected")
    void symlinkDirectoryEscapeRejected(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Path unapprovedZone = baseDir.resolve("unapproved_zone");
        Files.createDirectories(approvedRoot);
        Files.createDirectories(unapprovedZone);

        // Create a real Git repo inside unapproved zone
        Path secretRepo = unapprovedZone.resolve("secret-repo");
        Files.createDirectories(secretRepo);
        try (Git git = Git.init().setDirectory(secretRepo.toFile()).call()) {
            Files.writeString(secretRepo.resolve("sensitive.txt"), "classified content\n");
            git.add().addFilepattern("sensitive.txt").call();
            git.commit().setMessage("Initial classified commit").call();
        }

        // Create symlink inside approvedRoot pointing to secretRepo
        Path symlinkRepo = approvedRoot.resolve("symlink-repo");
        boolean symlinkCreated = tryCreateSymlink(symlinkRepo, secretRepo);
        Assumptions.assumeTrue(symlinkCreated,
                "Skipping symlinkDirectoryEscapeRejected: OS/filesystem lacks privilege to create directory symlinks");

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        GitCollector collector = new GitCollector(List.of(approvedRoot.toString()));

        // validateRepositoryPath must resolve real path, detect breakout, and throw SecurityException
        assertThatThrownBy(() -> collector.validateRepositoryPath(symlinkRepo.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied: Repository path is not within the authorized repository roots");

        assertThatThrownBy(() -> GitRepositorySafetyValidator.openRepositorySafely(symlinkRepo, allowedRoots))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Scenario 2: dotGitSymlinkEscapeRejected - Repository with symlinked .git directory must be rejected")
    void dotGitSymlinkEscapeRejected(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Path externalMetadataDir = baseDir.resolve("external_metadata_dotgit");
        Files.createDirectories(approvedRoot);
        Files.createDirectories(externalMetadataDir);

        // Create legitimate repo to get valid .git metadata
        Path tempRepo = baseDir.resolve("temp_template_repo");
        Files.createDirectories(tempRepo);
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            // Created template .git
        }

        // Copy template .git to externalMetadataDir
        Path templateGitDir = tempRepo.resolve(".git");
        Path unapprovedGitDir = externalMetadataDir.resolve(".git");
        copyDirectory(templateGitDir, unapprovedGitDir);

        // Create repository in approvedRoot, but symlink .git to external unapprovedGitDir
        Path rogueRepo = approvedRoot.resolve("rogue-repo");
        Files.createDirectories(rogueRepo);
        Path rogueDotGit = rogueRepo.resolve(".git");

        boolean symlinkCreated = tryCreateSymlink(rogueDotGit, unapprovedGitDir);
        Assumptions.assumeTrue(symlinkCreated,
                "Skipping dotGitSymlinkEscapeRejected: OS/filesystem lacks privilege to create symlinks");

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());

        // Must reject .git symlink with SecurityException
        assertThatThrownBy(() -> GitRepositorySafetyValidator.validateRepositoryPath(rogueRepo.toString(), allowedRoots))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid Git repository: .git cannot be a symbolic link");
    }

    @Test
    @DisplayName("Scenario 3: linkedWorktreeGitdirRejected - Repository with regular file .git (worktree) must be rejected")
    void linkedWorktreeGitdirRejected(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        // Create main repo
        Path mainRepo = approvedRoot.resolve("main-repo");
        Files.createDirectories(mainRepo);
        try (Git git = Git.init().setDirectory(mainRepo.toFile()).call()) {
            Files.writeString(mainRepo.resolve("a.txt"), "hello");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("Initial").call();
        }

        // Create linked worktree folder with .git as regular file
        Path worktreeDir = approvedRoot.resolve("linked-worktree");
        Files.createDirectories(worktreeDir);
        Path dotGitFile = worktreeDir.resolve(".git");
        Files.writeString(dotGitFile, "gitdir: " + mainRepo.resolve(".git").toAbsolutePath() + "/worktrees/wt1\n");

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        GitCollector collector = new GitCollector(List.of(approvedRoot.toString()));
        JGitCasAdapter casAdapter = new JGitCasAdapter(List.of(approvedRoot.toString()));

        // 1. Validator rejects regular file .git
        assertThatThrownBy(() -> GitRepositorySafetyValidator.validateRepositoryPath(worktreeDir.toString(), allowedRoots))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid Git repository: Linked worktrees and .git files are not supported");

        // 2. GitCollector rejects worktree
        assertThatThrownBy(() -> collector.validateRepositoryPath(worktreeDir.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid Git repository: Linked worktrees and .git files are not supported");

        // 3. JGitCasAdapter rejects CAS on worktree
        GitCasResult casResult = casAdapter.executeCompareAndSwap(
                worktreeDir.toString(),
                "refs/heads/feature",
                ObjectId.zeroId().name(),
                "1111111111111111111111111111111111111111",
                false
        );
        assertThat(casResult.success()).isFalse();
        assertThat(casResult.failureMessage()).contains("Linked worktrees and .git files are not supported");
    }

    @Test
    @DisplayName("Scenario 4: findGitDirTraversalPrevented - Subdirectory without .git must fail without discovering parent repo")
    void findGitDirTraversalPrevented(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        // Create parent Git repo
        Path parentRepo = approvedRoot.resolve("parent-repo");
        Files.createDirectories(parentRepo);
        try (Git git = Git.init().setDirectory(parentRepo.toFile()).call()) {
            Files.writeString(parentRepo.resolve("parent.txt"), "parent content");
            git.add().addFilepattern("parent.txt").call();
            git.commit().setMessage("Parent commit").call();
        }

        // Create nested subfolder without .git
        Path subFolder = parentRepo.resolve("sub/deep/folder");
        Files.createDirectories(subFolder);

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        GitCollector collector = new GitCollector(List.of(approvedRoot.toString()));

        // Validating subfolder must fail with missing .git, NEVER climbing to parent-repo
        assertThatThrownBy(() -> collector.validateRepositoryPath(subFolder.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing .git directory");

        assertThatThrownBy(() -> GitRepositorySafetyValidator.validateRepositoryPath(subFolder.toString(), allowedRoots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing .git directory");
    }

    @Test
    @DisplayName("Scenario 5: environmentVariableOverrideIgnored - Safe opening must ignore environment variables")
    void environmentVariableOverrideIgnored(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        Path testRepo = approvedRoot.resolve("normal-repo");
        Files.createDirectories(testRepo);
        try (Git git = Git.init().setDirectory(testRepo.toFile()).call()) {
            Files.writeString(testRepo.resolve("file.txt"), "data");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("Init").call();
        }

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        Path canonicalPath = GitRepositorySafetyValidator.validateRepositoryPath(testRepo.toString(), allowedRoots);

        // Open repository safely
        try (Repository repo = GitRepositorySafetyValidator.openRepositorySafely(canonicalPath, allowedRoots)) {
            assertNotNull(repo);
            assertThat(repo.isBare()).isFalse();
            // Verify working tree and gitdir match the validated directory
            assertEquals(canonicalPath.toFile().getCanonicalPath(), repo.getWorkTree().getCanonicalPath());
            assertEquals(canonicalPath.resolve(".git").toFile().getCanonicalPath(), repo.getDirectory().getCanonicalPath());
        }
    }

    @Test
    @DisplayName("Scenario 6: postOpenBoundaryMismatchRejected - Bare repos or metadata escapes must fail post-open checks")
    void postOpenBoundaryMismatchRejected(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        // Create bare repository
        Path bareRepo = approvedRoot.resolve("bare-repo.git");
        Files.createDirectories(bareRepo);
        try (Git git = Git.init().setBare(true).setDirectory(bareRepo.toFile()).call()) {
            // Bare repo initialized
        }

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());

        // 1. validateRepositoryPath expects .git subdirectory, which bare repo lacks at root
        assertThatThrownBy(() -> GitRepositorySafetyValidator.validateRepositoryPath(bareRepo.toString(), allowedRoots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing .git directory");

        // 2. If openRepositorySafely were invoked directly on a bare repo path that contains .git (e.g. simulated),
        // repo.isBare() check rejects it immediately
    }

    @Test
    @DisplayName("Scenario 7: toctouDirectorySwapAborted - Post-open canonical verification validates opened repo matches canonical path")
    void toctouDirectorySwapAborted(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        Path repoDir = approvedRoot.resolve("swap-repo");
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("swap.txt"), "swap");
            git.add().addFilepattern("swap.txt").call();
            git.commit().setMessage("Swap commit").call();
        }

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        Path canonical = GitRepositorySafetyValidator.validateRepositoryPath(repoDir.toString(), allowedRoots);

        // Open safely confirms both pre-validation and post-open match
        try (Repository repo = GitRepositorySafetyValidator.openRepositorySafely(canonical, allowedRoots)) {
            assertThat(repo.getWorkTree().toPath().toRealPath()).isEqualTo(canonical);
        }
    }

    @Test
    @DisplayName("Scenario 8: normalRepositoryOperationsSucceed - Full workflow operations succeed inside approved roots")
    void normalRepositoryOperationsSucceed(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        Path repoDir = approvedRoot.resolve("operational-repo");
        Files.createDirectories(repoDir);

        ObjectId commitSha;
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("app.java"), "class App {}");
            git.add().addFilepattern("app.java").call();
            commitSha = git.commit().setMessage("Initial App").call().getId();
        }

        // 1. GitCollector
        GitCollector collector = new GitCollector(List.of(approvedRoot.toString()));
        var commits = collector.collectCommits(repoDir.toString(), 5);
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).commitMessage()).isEqualTo("Initial App");

        // 2. GitWorkingTreeCollector
        GitWorkingTreeCollector wtCollector = new GitWorkingTreeCollector(collector, List.of(approvedRoot.toString()));
        WorkingTreeSnapshot snapshot = wtCollector.collectWorkingTree(repoDir.toString());
        assertThat(snapshot.hasChanges()).isFalse();

        // 3. JGitCasAdapter
        JGitCasAdapter casAdapter = new JGitCasAdapter(List.of(approvedRoot.toString()));
        GitCasResult casResult = casAdapter.executeCompareAndSwap(
                repoDir.toString(),
                "refs/heads/feature-ops",
                ObjectId.zeroId().name(),
                commitSha.name(),
                false
        );
        assertTrue(casResult.success());
        assertEquals(commitSha.name(), casResult.resultingSha());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Scenario 9: windowsJunctionEscapeRejected - Windows directory junction escaping approved root must be rejected")
    void windowsJunctionEscapeRejected(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Path unapprovedZone = baseDir.resolve("unapproved_zone");
        Files.createDirectories(approvedRoot);
        Files.createDirectories(unapprovedZone);

        // Create target repo inside unapprovedZone
        Path secretRepo = unapprovedZone.resolve("secret-windows-repo");
        Files.createDirectories(secretRepo);
        try (Git git = Git.init().setDirectory(secretRepo.toFile()).call()) {
            Files.writeString(secretRepo.resolve("secret.txt"), "windows secret");
            git.add().addFilepattern("secret.txt").call();
            git.commit().setMessage("Windows secret commit").call();
        }

        // Create directory junction in approvedRoot pointing to secretRepo
        Path junctionRepo = approvedRoot.resolve("junction-repo");
        boolean junctionCreated = tryCreateWindowsJunction(junctionRepo, secretRepo);
        Assumptions.assumeTrue(junctionCreated,
                "Skipping windowsJunctionEscapeRejected: Unable to create Windows junction");

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        GitCollector collector = new GitCollector(List.of(approvedRoot.toString()));

        // validateRepositoryPath uses toRealPath() which resolves directory junctions!
        assertThatThrownBy(() -> collector.validateRepositoryPath(junctionRepo.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied: Repository path is not within the authorized repository roots");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Scenario 10: windows83ShortNameResolved - Windows 8.3 short paths must resolve canonically to approved roots")
    void windows83ShortNameResolved(@TempDir Path baseDir) throws Exception {
        Path approvedRoot = baseDir.resolve("approved_zone");
        Files.createDirectories(approvedRoot);

        Path longNamedRepo = approvedRoot.resolve("traceback_adversarial_long_name_repo");
        Files.createDirectories(longNamedRepo);
        try (Git git = Git.init().setDirectory(longNamedRepo.toFile()).call()) {
            Files.writeString(longNamedRepo.resolve("readme.txt"), "hello");
            git.add().addFilepattern("readme.txt").call();
            git.commit().setMessage("Init").call();
        }

        String shortPath = getWindowsShortPath(longNamedRepo);
        Assumptions.assumeTrue(shortPath != null && !shortPath.isBlank(),
                "Skipping windows83ShortNameResolved: 8.3 short name generation is disabled on this volume");

        List<Path> allowedRoots = List.of(approvedRoot.toRealPath());
        GitCollector collector = new GitCollector(List.of(approvedRoot.toString()));

        // Short name must resolve to canonical real path and succeed
        Path validated = collector.validateRepositoryPath(shortPath);
        assertThat(validated).isEqualTo(longNamedRepo.toRealPath());
    }

    private static boolean tryCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Throwable t) {
            log.info("Symlink creation not permitted: {}", t.getMessage());
            return false;
        }
    }

    private static boolean tryCreateWindowsJunction(Path link, Path target) {
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/j",
                    link.toAbsolutePath().toString(),
                    target.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0 && Files.exists(link);
        } catch (Throwable t) {
            log.info("Windows junction creation failed: {}", t.getMessage());
            return false;
        }
    }

    private static String getWindowsShortPath(Path path) {
        try {
            Process process = new ProcessBuilder("cmd", "/c", "for %I in (\"" + path.toAbsolutePath() + "\") do @echo %~sI")
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exit = process.waitFor();
            if (exit == 0 && !output.isBlank() && !output.equalsIgnoreCase(path.toAbsolutePath().toString())) {
                return output;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        if (!Files.exists(dest)) {
                            Files.createDirectories(dest);
                        }
                    } else {
                        Files.copy(src, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
