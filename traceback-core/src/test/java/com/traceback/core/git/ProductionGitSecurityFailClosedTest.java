package com.traceback.core.git;

import com.traceback.core.git.workingtree.GitWorkingTreeCollector;
import com.traceback.core.remediation.git.JGitCasAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionGitSecurityFailClosedTest {

    @Test
    @DisplayName("Production profile with empty or null allowed roots must fail startup closed")
    void productionFailsWhenAllowedRootsEmptyOrNull() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        // 1. Null or empty list in GitRepositorySafetyValidator directly
        assertThatThrownBy(() -> GitRepositorySafetyValidator.initializeAllowedRoots(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production security failure: 'traceback.security.allowed-repo-roots' must be explicitly configured");

        assertThatThrownBy(() -> GitRepositorySafetyValidator.initializeAllowedRoots(Collections.emptyList(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production security failure: 'traceback.security.allowed-repo-roots' must be explicitly configured");

        // 2. GitCollector in prod
        assertThatThrownBy(() -> new GitCollector("", prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production security failure");

        // 3. GitWorkingTreeCollector in prod
        assertThatThrownBy(() -> new GitWorkingTreeCollector(null, "", prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production security failure");

        // 4. JGitCasAdapter in prod
        assertThatThrownBy(() -> new JGitCasAdapter("", prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production security failure");
    }

    @Test
    @DisplayName("Production profile with non-existent root path must fail startup closed")
    void productionFailsWhenConfiguredRootDoesNotExist(@TempDir Path tempDir) {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        Path nonExistent = tempDir.resolve("does_not_exist_zone");

        assertThatThrownBy(() -> GitRepositorySafetyValidator.initializeAllowedRoots(List.of(nonExistent.toString()), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured repository root does not exist on disk");

        assertThatThrownBy(() -> new GitCollector(nonExistent.toString(), prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured repository root does not exist on disk");

        assertThatThrownBy(() -> new JGitCasAdapter(nonExistent.toString(), prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured repository root does not exist on disk");
    }

    @Test
    @DisplayName("Production profile must never include . or java.io.tmpdir implicitly")
    void productionNeverIncludesImplicitRoots(@TempDir Path validDir) {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        List<Path> initialized = GitRepositorySafetyValidator.initializeAllowedRoots(List.of(validDir.toString()), true);
        Path canonicalValid = validDir.toAbsolutePath().normalize();
        try {
            canonicalValid = validDir.toRealPath();
        } catch (Exception ignored) {}

        assertThat(initialized).containsExactly(canonicalValid);

        // Neither '.' nor tmpdir should be present if not explicitly configured
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        try {
            projectRoot = Path.of(".").toRealPath();
        } catch (Exception ignored) {}

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        try {
            tmpDir = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        } catch (Exception ignored) {}

        if (!canonicalValid.equals(projectRoot)) {
            assertThat(initialized).doesNotContain(projectRoot);
        }
        if (!canonicalValid.equals(tmpDir)) {
            assertThat(initialized).doesNotContain(tmpDir);
        }
    }

    @Test
    @DisplayName("Development and test profile retains default frictionless fallback (. and tmpdir)")
    void developmentRetainsDefaultRoots() {
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        GitCollector devCollector = new GitCollector("", devEnv);
        assertThat(devCollector.getAllowedRoots()).isNotEmpty();

        JGitCasAdapter devCas = new JGitCasAdapter("", devEnv);
        assertThat(devCas.getAllowedRoots()).isNotEmpty();
    }
}
