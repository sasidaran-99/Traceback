package com.traceback.core.remediation;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Command for proposing an administrative break-glass recovery review.
 */
public record ProposeBreakGlassCommand(
        UUID operationId,
        String rationale,
        String evidenceDigest,
        String evidenceBundleRef,
        String evidenceBundleVersion
) {
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    public ProposeBreakGlassCommand {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(rationale, "rationale cannot be null");
        if (rationale.trim().isEmpty()) {
            throw new IllegalArgumentException("rationale cannot be blank");
        }

        String trimmedDigest = (evidenceDigest != null && !evidenceDigest.trim().isEmpty()) ? evidenceDigest.trim() : null;
        String trimmedRef = (evidenceBundleRef != null && !evidenceBundleRef.trim().isEmpty()) ? evidenceBundleRef.trim() : null;
        String trimmedVersion = (evidenceBundleVersion != null && !evidenceBundleVersion.trim().isEmpty()) ? evidenceBundleVersion.trim() : null;

        boolean anyPresent = trimmedDigest != null || trimmedRef != null || trimmedVersion != null;
        boolean allPresent = trimmedDigest != null && trimmedRef != null && trimmedVersion != null;

        if (anyPresent && !allPresent) {
            throw new IllegalArgumentException("Evidence fields (digest, bundle ref, bundle version) must all be provided together or all omitted");
        }

        if (allPresent) {
            if (!HEX_64_PATTERN.matcher(trimmedDigest).matches()) {
                throw new IllegalArgumentException("evidenceDigest must be exactly 64 hexadecimal characters");
            }
            if (trimmedRef.length() > 255) {
                throw new IllegalArgumentException("evidenceBundleRef must not exceed 255 characters");
            }
            if (trimmedVersion.length() > 64) {
                throw new IllegalArgumentException("evidenceBundleVersion must not exceed 64 characters");
            }
            evidenceDigest = trimmedDigest.toLowerCase(Locale.ROOT);
            evidenceBundleRef = trimmedRef;
            evidenceBundleVersion = trimmedVersion;
        } else {
            evidenceDigest = null;
            evidenceBundleRef = null;
            evidenceBundleVersion = null;
        }
    }
}
