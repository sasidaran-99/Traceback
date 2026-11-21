package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for cancelling a pending break-glass recovery proposal by its initiator.
 */
public record CancelBreakGlassRequest(
        @NotBlank(message = "Cancellation reason is required")
        @Size(min = 5, max = 2000, message = "Cancellation reason must be between 5 and 2000 characters")
        String cancellationReason
) {
    @JsonIgnore
    public String trimmedCancellationReason() {
        return cancellationReason != null ? cancellationReason.trim() : null;
    }
}
