package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for rejecting a pending break-glass recovery proposal.
 */
public record RejectBreakGlassRequest(
        @NotBlank(message = "Rejection reason is required")
        @Size(min = 5, max = 2000, message = "Rejection reason must be between 5 and 2000 characters")
        String rejectionReason
) {
    @JsonIgnore
    public String trimmedRejectionReason() {
        return rejectionReason != null ? rejectionReason.trim() : null;
    }
}
