package com.traceback.api.dto;

import com.traceback.core.remediation.RemediationOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record ProposeRemediationRequest(
        UUID investigationId,

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey,

        @NotBlank(message = "repositoryPath is required")
        String repositoryPath,

        @NotBlank(message = "targetRef is required")
        String targetRef,

        @NotBlank(message = "expectedOldSha is required")
        @Pattern(regexp = "^[0-9a-fA-F]{40}$", message = "expectedOldSha must be a 40-character hex SHA")
        String expectedOldSha,

        @NotBlank(message = "proposedNewSha is required")
        @Pattern(regexp = "^[0-9a-fA-F]{40}$", message = "proposedNewSha must be a 40-character hex SHA")
        String proposedNewSha,

        @NotNull(message = "operationType is required")
        RemediationOperationType operationType,

        String initiatedBy
) {
}
