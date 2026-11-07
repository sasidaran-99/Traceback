package com.traceback.api.controller;

import com.traceback.api.dto.HypothesisResponse;
import com.traceback.api.dto.InvestigationRequest;
import com.traceback.api.dto.InvestigationResponse;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.timeline.TimelineEvent;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for executing and querying deterministic investigations.
 */
@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    private final InvestigationService investigationService;

    public InvestigationController(InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    /**
     * Initiates a deterministic investigation using repository and telemetry inputs.
     */
    @PostMapping
    public ResponseEntity<InvestigationResponse> createInvestigation(@Valid @RequestBody InvestigationRequest request) {
        InvestigationResult result = investigationService.startInvestigation(
                request.repositoryPath(),
                request.stackTrace(),
                request.logFilePath(),
                request.logContent(),
                request.serviceName(),
                request.environment(),
                request.windowMinutes() != null ? request.windowMinutes() : 60,
                request.maxCommits() != null ? request.maxCommits() : 50
        );

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.investigationId())
                .toUri();

        return ResponseEntity.created(location).body(InvestigationResponse.fromResult(result));
    }

    /**
     * Retrieves an overview of an investigation by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<InvestigationResponse> getInvestigation(@PathVariable UUID id) {
        return investigationService.getInvestigation(id)
                .map(InvestigationResponse::fromResult)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retrieves the chronological timeline for an investigation.
     */
    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<TimelineEvent>> getTimeline(@PathVariable UUID id) {
        return investigationService.getInvestigation(id)
                .map(res -> ResponseEntity.ok(res.timeline()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retrieves all concrete evidence items tracked in the registry for an investigation.
     */
    @GetMapping("/{id}/evidence")
    public ResponseEntity<List<EvidenceItem>> getEvidence(@PathVariable UUID id) {
        return investigationService.getInvestigation(id)
                .map(res -> ResponseEntity.ok(res.evidenceItems()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retrieves ranked candidate root-cause hypotheses for an investigation.
     */
    @GetMapping("/{id}/hypotheses")
    public ResponseEntity<List<HypothesisResponse>> getHypotheses(@PathVariable UUID id) {
        return investigationService.getInvestigation(id)
                .map(res -> {
                    List<HypothesisResponse> list = res.hypotheses().stream()
                            .map(h -> {
                                List<String> evdIds = Collections.emptyList();
                                return HypothesisResponse.fromEntity(h, evdIds);
                            })
                            .toList();
                    return ResponseEntity.ok(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
