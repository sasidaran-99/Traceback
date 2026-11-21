package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * Request payload for proposing a dual-custody break-glass recovery review.
 */
public record ProposeBreakGlassRequest(
        @NotBlank(message = "Rationale is required")
        @Size(min = 10, max = 2000, message = "Rationale must be between 10 and 2000 characters")
        String rationale,

        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "Evidence digest must be exactly 64 hexadecimal characters")
        String evidenceDigest,

        @Size(max = 255, message = "Evidence bundle ref must not exceed 255 characters")
        String evidenceBundleRef,

        @Size(max = 64, message = "Evidence bundle version must not exceed 64 characters")
        String evidenceBundleVersion
) {
    @AssertTrue(message = "Evidence fields (digest, bundle ref, bundle version) must all be provided together or all omitted")
    public boolean isEvidenceTriadValid() {
        boolean hasDigest = evidenceDigest != null && !evidenceDigest.trim().isEmpty();
        boolean hasRef = evidenceBundleRef != null && !evidenceBundleRef.trim().isEmpty();
        boolean hasVer = evidenceBundleVersion != null && !evidenceBundleVersion.trim().isEmpty();

        boolean anyPresent = hasDigest || hasRef || hasVer;
        boolean allPresent = hasDigest && hasRef && hasVer;

        return !anyPresent || allPresent;
    }

    @JsonIgnore
    public String normalizedEvidenceDigest() {
        if (evidenceDigest == null || evidenceDigest.trim().isEmpty()) {
            return null;
        }
        return evidenceDigest.trim().toLowerCase(Locale.ROOT);
    }

    @JsonIgnore
    public String trimmedEvidenceBundleRef() {
        if (evidenceBundleRef == null || evidenceBundleRef.trim().isEmpty()) {
            return null;
        }
        return evidenceBundleRef.trim();
    }

    @JsonIgnore
    public String trimmedEvidenceBundleVersion() {
        if (evidenceBundleVersion == null || evidenceBundleVersion.trim().isEmpty()) {
            return null;
        }
        return evidenceBundleVersion.trim();
    }
}
