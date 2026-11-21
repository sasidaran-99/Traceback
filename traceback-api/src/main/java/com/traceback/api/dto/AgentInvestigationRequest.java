package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.traceback.core.investigation.InvestigationMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request payload to initiate an autonomous AI investigation.
 * Supports either an existing investigation ID or full parameters to run deterministically first.
 * Supports optional explicit forceRerun flag (defaults to false for safe idempotency).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentInvestigationRequest(
        UUID investigationId,
        InvestigationMode investigationMode,

        @Size(max = 1024, message = "repositoryPath must not exceed 1024 characters")
        String repositoryPath,

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

        Boolean forceRerun,
        Boolean forceNew
) {
    public AgentInvestigationRequest(
            UUID investigationId,
            InvestigationMode investigationMode,
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            Integer windowMinutes,
            Integer maxCommits,
            Boolean forceRerun
    ) {
        this(investigationId, investigationMode, repositoryPath, stackTrace,
                logFilePath, logContent, serviceName, environment, windowMinutes, maxCommits, forceRerun, false);
    }

    public AgentInvestigationRequest(
            UUID investigationId,
            InvestigationMode investigationMode,
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            Integer windowMinutes,
            Integer maxCommits
    ) {
        this(investigationId, investigationMode, repositoryPath, stackTrace,
                logFilePath, logContent, serviceName, environment, windowMinutes, maxCommits, false, false);
    }

    public AgentInvestigationRequest(
            UUID investigationId,
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            Integer windowMinutes,
            Integer maxCommits
    ) {
        this(investigationId, null, repositoryPath, stackTrace,
                logFilePath, logContent, serviceName, environment, windowMinutes, maxCommits, false, false);
    }

    public InvestigationMode resolvedMode() {
        return investigationMode != null ? investigationMode : InvestigationMode.RUNTIME_FAILURE;
    }

    public boolean isForceRerun() {
        return Boolean.TRUE.equals(forceRerun);
    }

    public boolean isForceNew() {
        return Boolean.TRUE.equals(forceNew) || isForceRerun();
    }
}
