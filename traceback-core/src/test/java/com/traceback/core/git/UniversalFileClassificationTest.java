package com.traceback.core.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalFileClassificationTest {

    private final GitCommitAnalyzer commitAnalyzer = new GitCommitAnalyzer();
    private final GitDiffAnalyzer diffAnalyzer = new GitDiffAnalyzer();

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/java/App.java",
            "src/main/kotlin/Service.kt",
            "scripts/deploy.groovy",
            "app/server.scala",
            "backend/views.py",
            "frontend/src/index.js",
            "frontend/src/App.jsx",
            "frontend/src/main.ts",
            "frontend/src/components/Card.tsx",
            "Controllers/HomeController.cs",
            "cmd/server/main.go",
            "src/lib.rs",
            "app/models/user.rb",
            "index.php",
            "src/core.c",
            "src/engine.cpp",
            "include/header.h",
            "include/matrix.hpp",
            "Sources/App/main.swift"
    })
    @DisplayName("Should classify all major programming languages as source code")
    void shouldClassifyMajorLanguagesAsSourceCode(String filePath) {
        assertThat(ChangedFile.isSourceCodePath(filePath))
                .as("File path %s should be recognized as source code", filePath)
                .isTrue();

        CommitEvidence commit = new CommitEvidence(
                "abc123456789",
                "abc1234",
                "author",
                "author@example.com",
                Instant.now(),
                "feat: update " + filePath,
                List.of(new ChangedFile(filePath, "MODIFY", 10, 2, "patch", false, Collections.emptyMap())),
                10,
                2,
                false,
                true
        );

        assertThat(commitAnalyzer.hasApplicationCode(commit)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "README.md",
            "docs/architecture.markdown",
            "notes.txt",
            "assets/logo.png",
            "styles/global.css",
            "styles/theme.scss",
            "spec.adoc"
    })
    @DisplayName("Should not classify cosmetic/documentation files as application source code")
    void shouldNotClassifyCosmeticAsSourceCode(String filePath) {
        assertThat(ChangedFile.isSourceCodePath(filePath)).isFalse();

        CommitEvidence commit = new CommitEvidence(
                "cosm123456789",
                "cosm123",
                "author",
                "author@example.com",
                Instant.now(),
                "docs: update documentation",
                List.of(new ChangedFile(filePath, "MODIFY", 5, 1, "patch", false, Collections.emptyMap())),
                5,
                1,
                false,
                false
        );

        assertThat(commitAnalyzer.isCosmeticOnly(commit)).isTrue();
        assertThat(commitAnalyzer.hasApplicationCode(commit)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application.properties",
            "config.yml",
            "docker-compose.yaml",
            "pom.xml",
            "package.json",
            ".env",
            "nginx.conf",
            "database.ini",
            "pyproject.toml",
            "Cargo.toml",
            "setup.cfg"
    })
    @DisplayName("Should classify modern configuration file types across ecosystems")
    void shouldClassifyConfigurationFiles(String filePath) {
        assertThat(ChangedFile.isConfigurationPath(filePath))
                .as("File %s should be recognized as configuration", filePath)
                .isTrue();
    }

    @Test
    @DisplayName("Should extract config changes from TOML and CFG style patches")
    void shouldExtractConfigChangesFromTomlAndCfg() {
        String tomlPatch = """
                --- a/pyproject.toml
                +++ b/pyproject.toml
                @@ -10,3 +10,3 @@
                 [database]
                -timeout = 5000
                +timeout = 3000
                 host = "postgres.internal"
                """;

        Map<String, String> changes = diffAnalyzer.extractConfigChanges(tomlPatch, "pyproject.toml");
        assertThat(changes).containsEntry("timeout", "5000 => 3000");
    }
}
