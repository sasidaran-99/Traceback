package com.traceback.api.controller;

import com.traceback.api.dto.AgentInvestigationRequest;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.tavily.ExternalResearchFinding;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
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

    public AgentInvestigationController(InvestigationAgent investigationAgent,
                                        InvestigationService investigationService) {
        this.investigationAgent = investigationAgent;
        this.investigationService = investigationService;
    }

    /**
     * Triggers an autonomous AI agent investigation.
     */
    @PostMapping("/agent")
    public ResponseEntity<InvestigationAgentReport> runAgentInvestigation(@Valid @RequestBody AgentInvestigationRequest request) {
        InvestigationAgentReport report;

        if (request.investigationId() != null) {
            report = investigationAgent.investigate(request.investigationId());
        } else {
            InvestigationResult deterministicResult = investigationService.startInvestigation(
                    request.repositoryPath(),
                    request.stackTrace(),
                    request.logFilePath(),
                    request.logContent(),
                    request.serviceName() != null ? request.serviceName() : "default-service",
                    request.environment() != null ? request.environment() : "production",
                    request.windowMinutes() != null ? request.windowMinutes() : 60,
                    request.maxCommits() != null ? request.maxCommits() : 50
            );
            report = investigationAgent.investigate(deterministicResult);
        }

        return ResponseEntity.ok(report);
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
     */
    @GetMapping("/{id}/agent-report")
    public ResponseEntity<InvestigationAgentReport> getAgentReport(@PathVariable UUID id) {
        return investigationAgent.getReport(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retrieves external technical research findings collected by Tavily for an investigation.
     */
    @GetMapping("/{id}/research")
    public ResponseEntity<List<ExternalResearchFinding>> getResearchFindings(@PathVariable UUID id) {
        return investigationAgent.getReport(id)
                .map(report -> ResponseEntity.ok(report.externalResearchFindings()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retrieves the sequential audit log of actions taken by the agent during an investigation.
     */
    @GetMapping("/{id}/agent-steps")
    public ResponseEntity<List<AgentInvestigationStep>> getAgentSteps(@PathVariable UUID id) {
        return investigationAgent.getReport(id)
                .map(report -> ResponseEntity.ok(report.investigationSteps()))
                .orElse(ResponseEntity.notFound().build());
    }
}
