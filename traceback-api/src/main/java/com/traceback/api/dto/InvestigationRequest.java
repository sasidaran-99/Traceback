package com.traceback.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for launching a deterministic failure investigation.
 */
public record InvestigationRequest(
        @NotBlank(message = "repositoryPath cannot be blank")
        String repositoryPath,

        String stackTrace,
        String logFilePath,
        String logContent,
        String serviceName,
        String environment,
        Integer windowMinutes,
        Integer maxCommits
) {
}
