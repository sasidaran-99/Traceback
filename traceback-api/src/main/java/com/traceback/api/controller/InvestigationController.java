package com.traceback.api.controller;

import com.traceback.api.dto.HypothesisResponse;
import com.traceback.api.dto.InvestigationRequest;
import com.traceback.api.dto.InvestigationResponse;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.logging.InvestigationLoggingContext;
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
    private final com.traceback.core.evidence.EvidenceRegistry evidenceRegistry;

    public InvestigationController(InvestigationService investigationService,
                                   com.traceback.core.evidence.EvidenceRegistry evidenceRegistry) {
        this.investigationService = investigationService;
        this.evidenceRegistry = evidenceRegistry;
    }

    /**
     * Initiates a deterministic investigation using repository and telemetry inputs.
     * Supports both POST /api/investigations and POST /api/investigations/analyze.
     */
    @PostMapping({ "", "/analyze" })
    public ResponseEntity<InvestigationResponse> createInvestigation(@Valid @RequestBody InvestigationRequest request) {
        validateRequest(request);

        com.traceback.core.investigation.InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> outcome =
                investigationService.startInvestigationWithOutcome(
                        request.repositoryPath(),
                        request.stackTrace(),
                        request.logFilePath(),
                        request.logContent(),
                        request.serviceName(),
                        request.environment(),
                        request.windowMinutes() != null ? request.windowMinutes() : 60,
                        request.maxCommits() != null ? request.maxCommits() : 50,
                        request.resolvedMode(),
                        request.isForceNew()
                );

        InvestigationResult result = outcome.value();

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.investigationId())
                .toUri();

        try (var ignored = InvestigationLoggingContext.withInvestigationId(result.investigationId())) {
            return ResponseEntity.created(location)
                    .header("X-TraceBack-Deduplicated", String.valueOf(outcome.deduplicated()))
                    .header("X-TraceBack-Investigation-Id", result.investigationId().toString())
                    .body(InvestigationResponse.fromResult(result));
        }
    }

    private void validateRequest(InvestigationRequest request) {
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
     * Lists all investigations tracked in memory.
     */
    @GetMapping
    public ResponseEntity<List<InvestigationResponse>> getAllInvestigations() {
        List<InvestigationResponse> list = investigationService.getAllInvestigations().stream()
                .map(InvestigationResponse::fromResult)
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * Retrieves an overview of an investigation by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<InvestigationResponse> getInvestigation(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return investigationService.getInvestigation(id)
                    .map(InvestigationResponse::fromResult)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
    }

    /**
     * Retrieves the chronological timeline for an investigation.
     */
    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<TimelineEvent>> getTimeline(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return investigationService.getInvestigation(id)
                    .map(res -> ResponseEntity.ok(res.timeline()))
                    .orElse(ResponseEntity.notFound().build());
        }
    }

    /**
     * Retrieves all concrete evidence items tracked in the registry for an investigation.
     */
    @GetMapping("/{id}/evidence")
    public ResponseEntity<List<EvidenceItem>> getEvidence(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            List<EvidenceItem> items = investigationService.getEvidence(id);
            if (items.isEmpty() && investigationService.getInvestigation(id).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(items);
        }
    }

    /**
     * Retrieves a single investigation-scoped evidence item.
     */
    @GetMapping("/{id}/evidence/{evidenceId}")
    public ResponseEntity<EvidenceItem> getEvidenceItem(@PathVariable UUID id, @PathVariable String evidenceId) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return evidenceRegistry.findItemById(evidenceId, id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
    }

    /**
     * Retrieves ranked candidate root-cause hypotheses for an investigation.
     */
    @GetMapping("/{id}/hypotheses")
    public ResponseEntity<List<HypothesisResponse>> getHypotheses(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            return investigationService.getInvestigation(id)
                    .map(res -> {
                        List<HypothesisResponse> list = res.hypotheses().stream()
                                .map(h -> HypothesisResponse.fromEntity(h, h.getSupportingEvidenceIds()))
                                .toList();
                        return ResponseEntity.ok(list);
                    })
                    .orElse(ResponseEntity.notFound().build());
        }
    }

    /**
     * Retrieves deterministic claim validation results for an investigation.
     */
    @GetMapping("/{id}/claims")
    public ResponseEntity<List<com.traceback.core.validation.InvestigationClaim>> getClaims(@PathVariable UUID id) {
        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            List<com.traceback.core.validation.InvestigationClaim> claims = investigationService.getClaims(id);
            if (claims.isEmpty() && investigationService.getInvestigation(id).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(claims);
        }
    }
}
