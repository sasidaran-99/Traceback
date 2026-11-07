package com.traceback.core.git;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Structured Git commit evidence model capturing author, message, timestamp, diffs, and changed files.
 */
public record CommitEvidence(
        String commitSha,
        String shortSha,
        String authorName,
        String authorEmail,
        Instant timestamp,
        String commitMessage,
        List<ChangedFile> changedFiles,
        int totalLinesAdded,
        int totalLinesDeleted,
        boolean hasConfigurationChanges,
        boolean hasCodeChanges
) {
    public CommitEvidence {
        if (changedFiles == null) {
            changedFiles = Collections.emptyList();
        }
    }

    public boolean touchesFile(String fileName) {
        if (fileName == null) return false;
        return changedFiles.stream()
                .anyMatch(cf -> cf.filePath().endsWith(fileName) || cf.filePath().contains(fileName));
    }
}
