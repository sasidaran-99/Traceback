package com.traceback.core.evidence;

import com.traceback.core.security.SecretRedactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Concrete evidence item tracked in the Traceback evidence registry.
 * Supports full 64-character hexadecimal SHA-256 for newly created evidence,
 * with backward-compatibility for legacy 16-character truncated hashes.
 */
public record EvidenceItem(
        String evidenceId,
        EvidenceType evidenceType,
        String source,
        String originalReference,
        Instant timestamp,
        String summary,
        Map<String, Object> metadata,
        String integrityHash
) {
    public EvidenceItem {
        originalReference = originalReference != null ? SecretRedactor.getInstance().redact(originalReference) : null;
        summary = summary != null ? SecretRedactor.getInstance().redact(summary) : null;
        metadata = metadata != null ? SecretRedactor.getInstance().redactMetadata(metadata) : Collections.emptyMap();
        if (integrityHash == null || integrityHash.isBlank()) {
            integrityHash = computeFullSha256(evidenceId + "|" + source + "|" + originalReference + "|" + summary);
        }
    }

    public static EvidenceItem create(String evidenceId, EvidenceType type, String source,
                                      String originalRef, Instant timestamp, String summary,
                                      Map<String, Object> metadata) {
        return new EvidenceItem(evidenceId, type, source, originalRef, timestamp, summary, metadata, null);
    }

    public static EvidenceItem createWithLegacyHash(String evidenceId, EvidenceType type, String source,
                                                    String originalRef, Instant timestamp, String summary,
                                                    Map<String, Object> metadata) {
        String legacyHash = computeLegacyTruncatedHash(evidenceId + "|" + source + "|" + originalRef + "|" + summary);
        return new EvidenceItem(evidenceId, type, source, originalRef, timestamp, summary, metadata, legacyHash);
    }

    public UUID investigationId() {
        if (metadata != null && metadata.containsKey("investigationId")) {
            Object val = metadata.get("investigationId");
            if (val instanceof UUID u) return u;
            if (val instanceof String s && !s.isBlank()) {
                try {
                    return UUID.fromString(s.trim());
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public UUID originatingInvestigationId() {
        if (metadata != null && metadata.containsKey("originatingInvestigationId")) {
            Object val = metadata.get("originatingInvestigationId");
            if (val instanceof UUID u) return u;
            if (val instanceof String s && !s.isBlank()) {
                try {
                    return UUID.fromString(s.trim());
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public boolean isOwnedBy(UUID targetInvestigationId) {
        if (targetInvestigationId == null) return false;
        UUID ownId = investigationId();
        return targetInvestigationId.equals(ownId);
    }

    public boolean isGlobal() {
        if (metadata == null) return false;
        Object invObj = metadata.get("investigationId");
        Object origObj = metadata.get("originatingInvestigationId");

        String invStr = invObj != null ? invObj.toString().trim() : null;
        String origStr = origObj != null ? origObj.toString().trim() : null;

        // A foreign-owned or investigation-owned record must never become global because only one field says GLOBAL
        if (isUuidString(invStr) || isUuidString(origStr)) {
            return false;
        }

        // Trusted global policy: investigationId must be GLOBAL, and originatingInvestigationId must be absent or GLOBAL
        boolean invIsGlobal = "GLOBAL".equalsIgnoreCase(invStr);
        boolean origIsGlobal = origStr == null || "GLOBAL".equalsIgnoreCase(origStr);

        return invIsGlobal && origIsGlobal;
    }

    private static boolean isUuidString(String s) {
        if (s == null || s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isGenuinelyCollectedFor(UUID targetInvestigationId) {
        if (targetInvestigationId == null || isGlobal()) return false;
        UUID ownId = investigationId();
        UUID origId = originatingInvestigationId();
        // Neither field may be missing or malformed
        if (ownId == null || origId == null) return false;
        return targetInvestigationId.equals(ownId) && targetInvestigationId.equals(origId);
    }

    public EvidenceItem withOwnership(UUID owningInvestigationId, UUID originatingId) {
        Map<String, Object> newMeta = new java.util.LinkedHashMap<>(metadata);
        if (owningInvestigationId != null) {
            newMeta.put("investigationId", owningInvestigationId.toString());
        }
        if (originatingId != null) {
            newMeta.put("originatingInvestigationId", originatingId.toString());
        } else if (!newMeta.containsKey("originatingInvestigationId") && metadata.containsKey("investigationId")) {
            newMeta.put("originatingInvestigationId", metadata.get("investigationId").toString());
        }
        return new EvidenceItem(evidenceId, evidenceType, source, originalReference, timestamp, summary, newMeta, integrityHash);
    }

    public boolean isLegacyTruncatedHash() {
        return integrityHash != null && integrityHash.length() == 16;
    }

    public boolean isFullSha256() {
        return integrityHash != null && integrityHash.length() == 64;
    }

    public static String computeFullSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static String computeLegacyTruncatedHash(String input) {
        String full = computeFullSha256(input);
        return full.length() >= 16 ? full.substring(0, 16) : full;
    }
}
