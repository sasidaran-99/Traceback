package com.traceback.core.git.workingtree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable aggregated forensic snapshot of a Git repository's current working directory state.
 */
public record WorkingTreeSnapshot(
        String repositoryRoot,
        String headCommitSha,
        Instant observedAt,
        List<WorkingTreeEvidence> changes
) {
    public WorkingTreeSnapshot {
        if (changes == null) {
            changes = Collections.emptyList();
        } else {
            changes = Collections.unmodifiableList(new ArrayList<>(changes));
        }
        if (observedAt == null) {
            observedAt = Instant.now();
        }
    }

    public static WorkingTreeSnapshot clean(String repositoryRoot, String headCommitSha) {
        return new WorkingTreeSnapshot(repositoryRoot, headCommitSha, Instant.now(), Collections.emptyList());
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public boolean hasMissingTrackedFiles() {
        return changes.stream().anyMatch(c -> c.changeType() == WorkingTreeChangeType.MISSING);
    }

    public int missingTrackedCount() {
        return (int) changes.stream().filter(c -> c.changeType() == WorkingTreeChangeType.MISSING).count();
    }

    public List<WorkingTreeEvidence> getMissingFiles() {
        return changes.stream()
                .filter(c -> c.changeType() == WorkingTreeChangeType.MISSING)
                .toList();
    }

    public List<WorkingTreeEvidence> getModifiedFiles() {
        return changes.stream()
                .filter(c -> c.changeType() == WorkingTreeChangeType.MODIFIED)
                .toList();
    }

    public List<WorkingTreeEvidence> getUntrackedFiles() {
        return changes.stream()
                .filter(c -> c.changeType() == WorkingTreeChangeType.UNTRACKED)
                .toList();
    }

    public List<WorkingTreeEvidence> getStagedFiles() {
        return changes.stream()
                .filter(c -> c.stagingState() == WorkingTreeStagingState.STAGED ||
                             c.changeType() == WorkingTreeChangeType.STAGED_ADD ||
                             c.changeType() == WorkingTreeChangeType.STAGED_MODIFY ||
                             c.changeType() == WorkingTreeChangeType.STAGED_DELETE)
                .toList();
    }

    public List<WorkingTreeEvidence> getConflictingFiles() {
        return changes.stream()
                .filter(c -> c.changeType() == WorkingTreeChangeType.CONFLICT)
                .toList();
    }

    /**
     * Groups changes by affected subsystem or architectural module.
     */
    public Map<String, List<WorkingTreeEvidence>> groupByAffectedModule() {
        return changes.stream()
                .collect(Collectors.groupingBy(
                        WorkingTreeEvidence::affectedModule,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    /**
     * Counts missing tracked files grouped by file extension.
     */
    public Map<String, Long> countMissingByExtension() {
        return getMissingFiles().stream()
                .collect(Collectors.groupingBy(
                        this::extractExtension,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
    }

    private String extractExtension(WorkingTreeEvidence ev) {
        String path = ev.relativePath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            return path.substring(dot).toLowerCase(Locale.ROOT);
        }
        return "<no-ext>";
    }

    public String generateSummary() {
        if (changes.isEmpty()) {
            return "Working tree is clean; no uncommitted changes detected.";
        }
        StringBuilder sb = new StringBuilder();
        int missing = missingTrackedCount();
        int mod = getModifiedFiles().size();
        int untracked = getUntrackedFiles().size();
        int staged = getStagedFiles().size();

        if (missing > 0) {
            sb.append(missing).append(" tracked file(s) missing from disk");
            if (headCommitSha != null && !headCommitSha.isBlank()) {
                sb.append(" (present in HEAD ").append(headCommitSha.substring(0, Math.min(7, headCommitSha.length()))).append(")");
            }
        }
        if (mod > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(mod).append(" modified file(s)");
        }
        if (staged > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(staged).append(" staged change(s)");
        }
        if (untracked > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(untracked).append(" untracked file(s)");
        }
        return sb.toString();
    }
}
