package com.traceback.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ExecuteRemediationRequest(
        @NotBlank(message = "workerId is required")
        String workerId
) {
}
