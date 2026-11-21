package com.traceback.core.remediation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionGitLockDirectoryFailClosedTest {

    @Test
    @DisplayName("Missing production configuration (null) must fail startup closed")
    void missingProductionConfigurationFailsClosed() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'traceback.git.lock-directory' must be explicitly configured");

        assertThatThrownBy(() -> new DefaultGitRepositoryLockManager((String) null, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'traceback.git.lock-directory' must be explicitly configured");
    }

    @Test
    @DisplayName("Blank production configuration must fail startup closed")
    void blankProductionConfigurationFailsClosed() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory("   ", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'traceback.git.lock-directory' must be explicitly configured");

        assertThatThrownBy(() -> new DefaultGitRepositoryLockManager("   ", prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'traceback.git.lock-directory' must be explicitly configured");
    }

    @Test
    @DisplayName("Relative path in production configuration must fail startup closed")
    void relativePathFailsClosed() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory("relative/path/locks", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be an absolute path");

        assertThatThrownBy(() -> new DefaultGitRepositoryLockManager("relative/path/locks", prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be an absolute path");
    }

    @Test
    @DisplayName("Configuring the system temporary directory directly must fail startup closed")
    void systemTemporaryDirectoryFailsClosed() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        String sysTmp = System.getProperty("java.io.tmpdir");

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory(sysTmp, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be the system temporary directory");

        assertThatThrownBy(() -> new DefaultGitRepositoryLockManager(sysTmp, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be the system temporary directory");
    }

    @Test
    @DisplayName("Configuring a path inside the system temporary directory must fail startup closed")
    void pathInsideSystemTemporaryDirectoryFailsClosed(@TempDir Path tempDir) throws IOException {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        // tempDir is created inside java.io.tmpdir by JUnit
        Path insideTmp = tempDir.resolve("traceback-locks");
        Files.createDirectories(insideTmp);

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory(insideTmp.toString(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be inside the system temporary directory");

        assertThatThrownBy(() -> new DefaultGitRepositoryLockManager(insideTmp.toString(), prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be inside the system temporary directory");
    }

    @Test
    @DisplayName("Nonexistent directory in production must fail startup closed")
    void nonexistentDirectoryFailsClosed(@TempDir Path tempDir) {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        Path simulatedTmp = tempDir.resolve("simulated_tmp");
        Path nonExistent = tempDir.resolve("does_not_exist_locks");

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory(nonExistent.toString(), true, simulatedTmp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured lock directory does not exist on disk");
    }

    @Test
    @DisplayName("Regular file supplied instead of directory in production must fail startup closed")
    void regularFileSuppliedInsteadOfDirectoryFailsClosed(@TempDir Path tempDir) throws IOException {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        Path simulatedTmp = tempDir.resolve("simulated_tmp");
        Path regularFile = tempDir.resolve("not_a_dir.txt");
        Files.writeString(regularFile, "content");

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory(regularFile.toString(), true, simulatedTmp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a directory");
    }

    @Test
    @DisplayName("Valid explicitly configured persistent directory initializes cleanly")
    void validExplicitlyConfiguredPersistentDirectoryInitializes(@TempDir Path tempDir) throws IOException {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        Path simulatedTmp = tempDir.resolve("simulated_tmp");
        Files.createDirectories(simulatedTmp);

        Path validPersistentDir = tempDir.resolve("persistent_locks");
        Files.createDirectories(validPersistentDir);

        Path resolved = GitLockDirectorySafetyValidator.resolveLockDirectory(validPersistentDir.toString(), true, simulatedTmp);
        assertThat(resolved).isEqualTo(validPersistentDir.toRealPath());

        DefaultGitRepositoryLockManager manager = new DefaultGitRepositoryLockManager(
                validPersistentDir.toString(),
                GitRepositoryLockManager.DEFAULT_TIMEOUT,
                true,
                simulatedTmp
        );
        assertThat(manager.getLockDirectory()).isEqualTo(validPersistentDir.toRealPath());
    }

    @Test
    @DisplayName("Lock file persists after lock release and is reused rather than recreated or deleted")
    void lockFilePersistsAfterRelease(@TempDir Path tempDir) throws Exception {
        Path simulatedTmp = tempDir.resolve("simulated_tmp");
        Files.createDirectories(simulatedTmp);

        Path persistentLockDir = tempDir.resolve("persistent_locks");
        Files.createDirectories(persistentLockDir);

        Path testRepo = tempDir.resolve("test_repo");
        Files.createDirectories(testRepo);

        DefaultGitRepositoryLockManager manager = new DefaultGitRepositoryLockManager(
                persistentLockDir.toString(),
                GitRepositoryLockManager.DEFAULT_TIMEOUT,
                true,
                simulatedTmp
        );

        Path lockFilePath;
        try (AutoCloseableLock lock = manager.acquireLock(testRepo.toString())) {
            assertTrue(lock.isValid());
            lockFilePath = lock.getLockFilePath();
            assertTrue(Files.exists(lockFilePath), "Lock file must exist on disk while lock is held");
        }

        // CRITICAL VERIFICATION: After close(), lock file MUST STILL EXIST!
        assertTrue(Files.exists(lockFilePath), "Lock file MUST persist after release to prevent TOCTOU races");

        // Reacquire lock on same repo: must reuse existing file without recreating or deleting
        try (AutoCloseableLock lock2 = manager.acquireLock(testRepo.toString())) {
            assertTrue(lock2.isValid());
            assertEquals(lockFilePath, lock2.getLockFilePath());
        }
        assertTrue(Files.exists(lockFilePath), "Lock file MUST still persist after second acquisition and release");
    }

    @Test
    @DisplayName("Symbolic link lock directory is rejected where platform supports detection")
    void symbolicLinkDirectoryRejectedWhereSupported(@TempDir Path tempDir) throws IOException {
        Path simulatedTmp = tempDir.resolve("simulated_tmp");
        Files.createDirectories(simulatedTmp);

        Path targetDir = tempDir.resolve("real_target_dir");
        Files.createDirectories(targetDir);

        Path linkDir = tempDir.resolve("symlink_dir");
        try {
            Files.createSymbolicLink(linkDir, targetDir);
        } catch (UnsupportedOperationException | FileSystemException e) {
            // On Windows without Developer Mode, symbolic link creation is restricted
            return;
        }

        assertThatThrownBy(() -> GitLockDirectorySafetyValidator.resolveLockDirectory(linkDir.toString(), true, simulatedTmp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    @DisplayName("Development and test profile retains default tmpdir fallback")
    void developmentRetainsDefaultTmpdirFallback() {
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        Path resolved = GitLockDirectorySafetyValidator.resolveLockDirectory(null, false);
        assertThat(resolved).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "traceback-locks").toAbsolutePath().normalize());

        DefaultGitRepositoryLockManager manager = new DefaultGitRepositoryLockManager((String) null, devEnv);
        assertThat(manager.getLockDirectory()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "traceback-locks").toAbsolutePath().normalize());
    }
}
