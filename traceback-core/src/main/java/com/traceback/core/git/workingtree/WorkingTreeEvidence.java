package com.traceback.core.git.workingtree;

import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Structured forensic evidence representing an uncommitted filesystem change in a Git working tree.
 */
public record WorkingTreeEvidence(
        String evidenceId,
        String repositoryRoot,
        String relativePath,
        WorkingTreeChangeType changeType,
        WorkingTreeStagingState stagingState,
        String headCommitSha,
        boolean physicallyExists,
        boolean isReadable,
        boolean isSymlink,
        Instant observedAt,
        String source,
        String affectedModule,
        boolean expectedInHead,
        Long fileSize,
        Instant lastModified
) {
    public WorkingTreeEvidence {
        if (source == null || source.isBlank()) {
            source = "WORKING_TREE";
        }
        if (observedAt == null) {
            observedAt = Instant.now();
        }
        if (evidenceId == null || evidenceId.isBlank()) {
            evidenceId = generateEvidenceId(changeType, relativePath);
        }
    }

    public static String generateEvidenceId(WorkingTreeChangeType type, String relativePath) {
        String cleanPath = relativePath != null ? relativePath.replace('\\', '/') : "unknown";
        String hash = computeShortHash(cleanPath);
        String prefix = switch (type) {
            case MISSING -> "EVD-WT-MISSING-";
            case MODIFIED -> "EVD-WT-MOD-";
            case UNTRACKED -> "EVD-WT-UNTRACKED-";
            case STAGED_ADD -> "EVD-WT-STG-ADD-";
            case STAGED_MODIFY -> "EVD-WT-STG-MOD-";
            case STAGED_DELETE -> "EVD-WT-STG-DEL-";
            case CONFLICT -> "EVD-WT-CONFLICT-";
        };
        return prefix + hash;
    }

    private static String computeShortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Converts this forensic record to an EvidenceItem for tracking and validation in EvidenceRegistry.
     * Never exposes absolute repository root paths in the public summary to avoid leaking host structure.
     */
    public EvidenceItem toEvidenceItem(UUID investigationId) {
        String summary = buildSummary();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("relativePath", relativePath);
        metadata.put("changeType", changeType.name());
        metadata.put("stagingState", stagingState.name());
        metadata.put("headCommitSha", headCommitSha != null ? headCommitSha : "NONE");
        metadata.put("physicallyExists", physicallyExists);
        metadata.put("isReadable", isReadable);
        metadata.put("isSymlink", isSymlink);
        metadata.put("affectedModule", affectedModule != null ? affectedModule : "root");
        metadata.put("expectedInHead", expectedInHead);
        if (fileSize != null) {
            metadata.put("fileSize", fileSize);
        }
        if (lastModified != null) {
            metadata.put("lastModified", lastModified.toString());
        }
        metadata.put("investigationId", investigationId != null ? investigationId.toString() : "GLOBAL");

        return EvidenceItem.create(
                evidenceId,
                EvidenceType.WORKING_TREE_CHANGE,
                source,
                relativePath,
                observedAt,
                summary,
                Collections.unmodifiableMap(metadata)
        );
    }

    private String buildSummary() {
        return switch (changeType) {
            case MISSING -> String.format(
                    "Working tree missing file: %s (tracked in HEAD %s, absent from working directory without committed deletion)",
                    relativePath, headCommitSha != null ? headCommitSha.substring(0, Math.min(7, headCommitSha.length())) : "UNKNOWN"
            );
            case MODIFIED -> String.format(
                    "Working tree modified file: %s (uncommitted modifications on disk)", relativePath
            );
            case UNTRACKED -> String.format(
                    "Working tree untracked file: %s (present on disk, not tracked by Git)", relativePath
            );
            case STAGED_DELETE -> String.format(
                    "Staged deletion: %s (removal staged in Git index)", relativePath
            );
            case STAGED_ADD -> String.format(
                    "Staged add: %s (new file staged in Git index)", relativePath
            );
            case STAGED_MODIFY -> String.format(
                    "Staged modification: %s (modified file staged in Git index)", relativePath
            );
            case CONFLICT -> String.format(
                    "Working tree conflict: %s (unresolved merge/rebase conflict)", relativePath
            );
        };
    }
}
