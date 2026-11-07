package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Request payload to initiate an autonomous AI investigation.
 * Supports either an existing investigation ID or full parameters to run deterministically first.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentInvestigationRequest(
        UUID investigationId,
        String repositoryPath,
        String stackTrace,
        String logFilePath,
        String logContent,
        String serviceName,
        String environment,
        Integer windowMinutes,
        Integer maxCommits
) {}
