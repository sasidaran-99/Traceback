package com.traceback.api.dto;

import com.traceback.core.investigation.InvestigationMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for launching a deterministic failure investigation.
 */
public record InvestigationRequest(
        @NotBlank(message = "repositoryPath cannot be blank")
        @Size(max = 1024, message = "repositoryPath must not exceed 1024 characters")
        String repositoryPath,

        InvestigationMode investigationMode,

        @Size(max = 262144, message = "stackTrace must not exceed 256KB")
        String stackTrace,

        @Size(max = 1024, message = "logFilePath must not exceed 1024 characters")
        String logFilePath,

        @Size(max = 2097152, message = "logContent must not exceed 2MB")
        String logContent,

        @Size(max = 128, message = "serviceName must not exceed 128 characters")
        String serviceName,

        @Size(max = 64, message = "environment must not exceed 64 characters")
        String environment,

        @Min(value = 1, message = "windowMinutes must be at least 1")
        @Max(value = 1440, message = "windowMinutes must not exceed 1440")
        Integer windowMinutes,

        @Min(value = 1, message = "maxCommits must be at least 1")
        @Max(value = 200, message = "maxCommits must not exceed 200")
        Integer maxCommits,

        Boolean forceNew
) {
    public InvestigationRequest(
            String repositoryPath,
            InvestigationMode investigationMode,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            Integer windowMinutes,
            Integer maxCommits
    ) {
        this(repositoryPath, investigationMode, stackTrace, logFilePath,
                logContent, serviceName, environment, windowMinutes, maxCommits, false);
    }

    public InvestigationRequest(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            Integer windowMinutes,
            Integer maxCommits
    ) {
        this(repositoryPath, null, stackTrace, logFilePath,
                logContent, serviceName, environment, windowMinutes, maxCommits, false);
    }

    public boolean isForceNew() {
        return Boolean.TRUE.equals(forceNew);
    }

    public InvestigationMode resolvedMode() {
        return investigationMode != null ? investigationMode : InvestigationMode.RUNTIME_FAILURE;
    }
}
