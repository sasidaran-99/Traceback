package com.traceback.core.git;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Categorizes commit changes by subsystem and identifies non-functional or cosmetic modifications.
 */
@Component
public class GitCommitAnalyzer {

    private static final Set<String> COSMETIC_EXTENSIONS = Set.of(
            ".md", ".markdown", ".txt", ".adoc", ".png", ".jpg", ".jpeg", ".svg", ".css", ".scss"
    );

    /**
     * Determines whether all files modified in a commit are non-functional or cosmetic.
     */
    public boolean isCosmeticOnly(CommitEvidence commit) {
        if (commit.changedFiles().isEmpty()) {
            return false;
        }
        return commit.changedFiles().stream()
                .allMatch(file -> isCosmeticPath(file.filePath()));
    }

    /**
     * Determines whether a commit modified application code (e.g., .java, .kt, .groovy, .ts, .js).
     */
    public boolean hasApplicationCode(CommitEvidence commit) {
        return commit.changedFiles().stream()
                .anyMatch(file -> isCodePath(file.filePath()));
    }

    /**
     * Identifies the primary subsystem hinted by commit message and files (e.g., DATABASE, AUTH, UI, CONFIG).
     */
    public String inferSubsystem(CommitEvidence commit) {
        String msg = commit.commitMessage().toLowerCase();
        if (msg.contains("db") || msg.contains("database") || msg.contains("hikari") || msg.contains("datasource") || msg.contains("timeout")) {
            return "DATABASE";
        }
        if (msg.contains("auth") || msg.contains("login") || msg.contains("token") || msg.contains("jwt") || msg.contains("password")) {
            return "AUTHENTICATION";
        }
        if (msg.contains("ui") || msg.contains("css") || msg.contains("frontend") || msg.contains("view") || msg.contains("layout")) {
            return "UI";
        }
        if (commit.hasConfigurationChanges()) {
            return "CONFIGURATION";
        }
        return "GENERAL";
    }

    private boolean isCosmeticPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return COSMETIC_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private boolean isCodePath(String path) {
        return ChangedFile.isSourceCodePath(path);
    }
}
