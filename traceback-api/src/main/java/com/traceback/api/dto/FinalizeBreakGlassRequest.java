package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.traceback.core.remediation.RemediationBreakGlassDecision;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for finalizing a dual-custody break-glass recovery review.
 * Only KEEP_FROZEN is supported in Stage 4.3.
 */
public record FinalizeBreakGlassRequest(
        @NotNull(message = "Decision is required")
        RemediationBreakGlassDecision decision,

        @Size(max = 2000, message = "Execution notes must not exceed 2000 characters")
        String executionNotes
) {
    @AssertTrue(message = "Only KEEP_FROZEN decision is accepted")
    public boolean isDecisionValid() {
        return decision == RemediationBreakGlassDecision.KEEP_FROZEN;
    }

    @JsonIgnore
    public String trimmedExecutionNotes() {
        return (executionNotes != null && !executionNotes.trim().isEmpty()) ? executionNotes.trim() : null;
    }
}
