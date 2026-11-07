package com.traceback.core.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;

/**
 * Concrete evidence item tracked in the Traceback evidence registry.
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
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
        if (integrityHash == null || integrityHash.isBlank()) {
            integrityHash = computeHash(evidenceId + "|" + source + "|" + originalReference + "|" + summary);
        }
    }

    public static EvidenceItem create(String evidenceId, EvidenceType type, String source,
                                      String originalRef, Instant timestamp, String summary,
                                      Map<String, Object> metadata) {
        return new EvidenceItem(evidenceId, type, source, originalRef, timestamp, summary, metadata, null);
    }

    private static String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
