package com.traceback.api.controller;

import com.traceback.api.dto.AgentInvestigationRequest;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.tavily.ExternalResearchFinding;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.logging.InvestigationLoggingContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for autonomous AI investigation workflows, research findings, and agent audit trails.
 */
@RestController
@RequestMapping("/api/investigations")
public class AgentInvestigationController {

    private final InvestigationAgent investigationAgent;
    private final InvestigationService investigationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.traceback.core.investigation.InvestigationDeduplicator deduplicator;

    public AgentInvestigationController(InvestigationAgent investigationAgent,
                                        InvestigationService investigationService) {
        this.investigationAgent = investigationAgent;
        this.investigationService = investigationService;
    }

    public void setDeduplicator(com.traceback.core.investigation.InvestigationDeduplicator deduplicator) {
        this.deduplicator = deduplicator;
    }

    /**
     * Triggers an autonomous AI agent investigation.
     * Supports both POST /api/investigations/agent and POST /api/investigations/agent/run.
     * Safe idempotency: When an investigation ID is provided and forceRerun is not explicitly true,
     * returns the existing authoritative agent report without re-executing.
     */
    @PostMapping({ "/agent", "/agent/run" })
    public ResponseEntity<InvestigationAgentReport> runAgentInvestigation(@Valid @RequestBody AgentInvestigationRequest request) {
        InvestigationAgentReport report;
        boolean deduplicated = false;

        if (request.investigationId() != null) {
            try (var ignored = InvestigationLoggingContext.withInvestigationId(request.investigationId())) {
                if (!request.isForceRerun()) {
                    java.util.Optional<InvestigationAgentReport> existingReport = investigationAgent.getReport(request.investigationId());
                    if (existingReport.isPresent()) {
                        return ResponseEntity.ok()
                                .header("X-TraceBack-Deduplicated", "true")
                                .header("X-TraceBack-Investigation-Id", request.investigationId().toString())
                                .body(existingReport.get());
                    }
                }
                report = investigationAgent.investigate(request.investigationId());
                return ResponseEntity.ok()
                        .header("X-TraceBack-Deduplicated", "false")
                        .header("X-TraceBack-Investigation-Id", report.investigationId().toString())
                        .body(report);
            }
        }

        validateAgentRequest(request);

        if (deduplicator != null && !request.isForceNew()) {
            String fingerprint = com.traceback.core.investigation.RequestFingerprint.compute(
                    request.repositoryPath(),
                    request.stackTrace(),
                    request.logFilePath(),
                    request.logContent(),
                    request.serviceName() != null ? request.serviceName() : "default-service",
                    request.environment() != null ? request.environment() : "production",
                    request.windowMinutes() != null ? request.windowMinutes() : 60,
                    request.maxCommits() != null ? request.maxCommits() : 50,
                    request.resolvedMode()
            );

            com.traceback.core.investigation.InvestigationDeduplicator.DeduplicationOutcome<InvestigationAgentReport> outcome =
                    deduplicator.executeAgentReportWithOutcome(fingerprint, () -> {
                        InvestigationResult deterministicResult = investigationService.startInvestigation(
                                request.repositoryPath(),
                                request.stackTrace(),
                                request.logFilePath(),
                                request.logContent(),
                                request.serviceName() != null ? request.serviceName() : "default-service",
                                request.environment() != null ? request.environment() : "production",
                                request.windowMinutes() != null ? request.windowMinutes() : 60,
                                request.maxCommits() != null ? request.maxCommits() : 50,
                                request.resolvedMode(),
                                false
                        );
                        try (var ignored = InvestigationLoggingContext.withInvestigationId(deterministicResult.investigationId())) {
                            return investigationAgent.investigate(deterministicResult);
                        }
                    });
            report = outcome.value();
            deduplicated = outcome.deduplicated();
        } else {
            InvestigationResult deterministicResult = investigationService.startInvestigation(
                    request.repositoryPath(),
                    request.stackTrace(),
                    request.logFilePath(),
                    request.logContent(),
                    request.serviceName() != null ? request.serviceName() : "default-service",
                    request.environment() != null ? request.environment() : "production",
                    request.windowMinutes() != null ? request.windowMinutes() : 60,
                    request.maxCommits() != null ? request.maxCommits() : 50,
                    request.resolvedMode(),
                    request.isForceNew()
            );
            try (var ignored = InvestigationLoggingContext.withInvestigationId(deterministicResult.investigationId())) {
                report = investigationAgent.investigate(deterministicResult);
            }
            deduplicated = false;
        }

        try (var ignored = InvestigationLoggingContext.withInvestigationId(report.investigationId())) {
            return ResponseEntity.ok()
                    .header("X-TraceBack-Deduplicated", String.valueOf(deduplicated))
                    .header("X-TraceBack-Investigation-Id", report.investigationId().toString())
                    .body(report);
        }
    }

    private void validateAgentRequest(AgentInvestigationRequest request) {
        if (request.repositoryPath() == null || request.repositoryPath().isBlank()) {
            throw new IllegalArgumentException("repositoryPath cannot be blank");
        }
        InvestigationMode mode = request.resolvedMode();
        if (mode == InvestigationMode.WORKING_TREE_FORENSICS) {
            if (request.serviceName() == null || request.serviceName().isBlank()) {
                throw new IllegalArgumentException("serviceName cannot be blank in WORKING_TREE_FORENSICS mode");
            }
            if (request.environment() == null || request.environment().isBlank()) {
                throw new IllegalArgumentException("environment cannot be blank in WORKING_TREE_FORENSICS mode");
            }
        } else if (request.investigationMode() == InvestigationMode.RUNTIME_FAILURE) {
            boolean hasLogs = (request.logContent() != null && !request.logContent().isBlank())
                    || (request.logFilePath() != null && !request.logFilePath().isBlank());
            boolean hasStack = request.stackTrace() != null && !request.stackTrace().isBlank();
            if (!hasLogs && !hasStack) {
                throw new IllegalArgumentException("Please provide either log content or a stack trace for runtime failure investigations.");
            }
        }
    }

    /**
     * Lists all autonomous AI agent reports.
     */
    @GetMapping("/agent")
    public ResponseEntity<List<InvestigationAgentReport>> getAllAgentReports() {
        return ResponseEntity.ok(investigationAgent.getAllReports());
    }

    /**
     * Retrieves the AI agent's structured report for an investigation.
     * Supports both GET /api/investigations/{id}/agent-report and GET /api/investigations/agent/{id}.
     */
    @GetMapping({ "/{id}/agent-report", "/agent/{id}" })
    public ResponseEntity<InvestigationAgentReport> getAgentReport(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return investigationAgent.getReport(id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
    }

    /**
     * Retrieves the chronological history of agent reports for an investigation.
     * Supports both GET /api/investigations/{id}/agent-reports and GET /api/investigations/agent/{id}/history.
     */
    @GetMapping({ "/{id}/agent-reports", "/agent/{id}/history" })
    public ResponseEntity<List<InvestigationAgentReport>> getAgentReportHistory(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return ResponseEntity.ok(investigationAgent.getReportHistory(id));
        }
    }

    /**
     * Retrieves external technical research findings collected by Tavily for an investigation.
     * Supports both GET /api/investigations/{id}/research and GET /api/investigations/agent/{id}/research.
     */
    @GetMapping({ "/{id}/research", "/agent/{id}/research" })
    public ResponseEntity<List<ExternalResearchFinding>> getResearchFindings(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return investigationAgent.getReport(id)
                    .map(report -> ResponseEntity.ok(report.externalResearchFindings()))
                    .orElse(ResponseEntity.notFound().build());
        }
    }

    /**
     * Retrieves the sequential audit log of actions taken by the agent during an investigation.
     * Supports both GET /api/investigations/{id}/agent-steps and GET /api/investigations/agent/{id}/audit.
     */
    @GetMapping({ "/{id}/agent-steps", "/agent/{id}/audit", "/agent/{id}/steps" })
    public ResponseEntity<List<AgentInvestigationStep>> getAgentSteps(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return investigationAgent.getReport(id)
                    .map(report -> ResponseEntity.ok(report.investigationSteps()))
                    .orElse(ResponseEntity.notFound().build());
        }
    }
}
