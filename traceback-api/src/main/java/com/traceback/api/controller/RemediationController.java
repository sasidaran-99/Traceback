package com.traceback.api.controller;

import com.traceback.api.dto.ExecuteRemediationRequest;
import com.traceback.api.dto.ProposeRemediationRequest;
import com.traceback.api.dto.RemediationEventResponse;
import com.traceback.api.dto.RemediationOperationResponse;
import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.domain.RemediationOperationEventEntity;
import com.traceback.core.remediation.RemediationAdminService;
import com.traceback.core.remediation.RemediationExecutionCoordinator;
import com.traceback.core.remediation.RemediationJournalService;
import com.traceback.core.remediation.RemediationRecoveryCoordinator;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.core.security.RemediationAuthenticationException;
import com.traceback.core.security.RemediationAuthorizationException;
import com.traceback.core.security.RemediationPrincipal;
import com.traceback.core.security.RemediationSecurityContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * REST controller for managing, proposing, executing, and auditing durable remediation operations.
 */
@RestController
@RequestMapping("/api/remediations")
public class RemediationController {

    private static final Logger log = LoggerFactory.getLogger(RemediationController.class);

    private final RemediationExecutionCoordinator executionCoordinator;
    private final RemediationRecoveryCoordinator recoveryCoordinator;
    private final RemediationAdminService adminService;
    private final RemediationJournalService journalService;
    private final RemediationOperationRepository operationRepository;
    private final InvestigationRepository investigationRepository;

    public RemediationController(
            RemediationExecutionCoordinator executionCoordinator,
            RemediationRecoveryCoordinator recoveryCoordinator,
            RemediationAdminService adminService,
            RemediationJournalService journalService,
            RemediationOperationRepository operationRepository,
            InvestigationRepository investigationRepository
    ) {
        this.executionCoordinator = executionCoordinator;
        this.recoveryCoordinator = recoveryCoordinator;
        this.adminService = adminService;
        this.journalService = journalService;
        this.operationRepository = operationRepository;
        this.investigationRepository = investigationRepository;
    }

    /**
     * Discovers remediation operations for a specific investigation, ordered newest first.
     * Rejects missing or null investigationId parameter to prevent full-table exposure.
     * Redacts filesystem repository paths to prevent host environment enumeration.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<RemediationOperationResponse>> listOperations(
            @RequestParam(required = false) UUID investigationId
    ) {
        if (investigationId == null) {
            throw new IllegalArgumentException("investigationId parameter is mandatory");
        }
        List<RemediationOperationEntity> operations = operationRepository
                .findByInvestigationIdOrderByCreatedAtDesc(investigationId);
        List<RemediationOperationResponse> response = operations.stream()
                .map(RemediationOperationResponse::fromEntitySanitized)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Proposes a new remediation operation.
     */
    @PostMapping("/proposals")
    public ResponseEntity<RemediationOperationResponse> proposeRemediation(
            @Valid @RequestBody ProposeRemediationRequest request
    ) {
        log.info("Proposing remediation operation: ref={}, idempotencyKey={}", request.targetRef(), request.idempotencyKey());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            throw new RemediationAuthenticationException("Authentication required: No authenticated principal found");
        }
        String actor = auth.getName().trim();

        // Enforce: client-provided initiatedBy cannot override or spoof the authenticated subject
        if (request.initiatedBy() != null && !request.initiatedBy().isBlank() && !request.initiatedBy().equals(actor)) {
            log.warn("Rejected proposal: client-supplied initiatedBy '{}' does not match authenticated subject '{}'", request.initiatedBy(), actor);
            throw new RemediationAuthorizationException("Identity spoofing detected: initiatedBy does not match authenticated subject");
        }

        if (request.investigationId() != null) {
            var investigation = investigationRepository.findById(request.investigationId())
                    .orElseThrow(() -> new NoSuchElementException("Investigation not found: " + request.investigationId()));
            if (investigation.getRepositoryPath() != null && !investigation.getRepositoryPath().isBlank()) {
                java.nio.file.Path invRepo = java.nio.file.Path.of(investigation.getRepositoryPath()).toAbsolutePath().normalize();
                java.nio.file.Path reqRepo = java.nio.file.Path.of(request.repositoryPath()).toAbsolutePath().normalize();
                if (!invRepo.equals(reqRepo)) {
                    throw new IllegalArgumentException("Investigation repositoryPath '" + investigation.getRepositoryPath() +
                            "' does not match proposal repositoryPath '" + request.repositoryPath() + "'");
                }
            }
        }

        RemediationOperationEntity operation = executionCoordinator.proposeOperation(
                request.investigationId(),
                request.idempotencyKey(),
                request.repositoryPath(),
                request.targetRef(),
                request.expectedOldSha(),
                request.proposedNewSha(),
                request.operationType(),
                actor
        );

        URI location = URI.create("/api/remediations/" + operation.getId());
        return ResponseEntity.created(location).body(RemediationOperationResponse.fromEntity(operation));
    }

    /**
     * Executes an approved remediation operation across the four-phase protocol.
     */
    @PostMapping("/{operationId}/execute")
    public ResponseEntity<RemediationOperationResponse> executeRemediation(
            @PathVariable UUID operationId,
            @Valid @RequestBody ExecuteRemediationRequest request
    ) {
        log.info("Executing remediation operation {}: workerId={}", operationId, request.workerId());
        RemediationOperationEntity executed = executionCoordinator.executeOperation(operationId, request.workerId());
        return ResponseEntity.ok(RemediationOperationResponse.fromEntity(executed));
    }

    /**
     * Retrieves the current status of an operation.
     */
    @GetMapping("/{operationId}")
    public ResponseEntity<RemediationOperationResponse> getOperationStatus(@PathVariable UUID operationId) {
        RemediationOperationEntity entity = operationRepository.findById(operationId)
                .orElseThrow(() -> new NoSuchElementException("Remediation operation not found: " + operationId));
        return ResponseEntity.ok(RemediationOperationResponse.fromEntity(entity));
    }

    /**
     * Retrieves the complete append-only audit trail for an operation.
     */
    @GetMapping("/{operationId}/events")
    public ResponseEntity<List<RemediationEventResponse>> getOperationEvents(@PathVariable UUID operationId) {
        List<RemediationOperationEventEntity> events = journalService.getEventsForOperation(operationId);
        List<RemediationEventResponse> response = events.stream()
                .map(RemediationEventResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Requests recovery for an interrupted operation.
     */
    @PostMapping("/{operationId}/recover")
    public ResponseEntity<RemediationOperationResponse> recoverOperation(@PathVariable UUID operationId) {
        log.info("Recovery requested for operation: {}", operationId);
        RemediationOperationEntity recovered = recoveryCoordinator.auditAndRecoverOperation(operationId);
        return ResponseEntity.ok(RemediationOperationResponse.fromEntity(recovered));
    }

    /**
     * Freezes a target ref against all promotion and remediation mutations.
     */
    @PostMapping("/refs/freeze")
    public ResponseEntity<Map<String, Object>> freezeRef(
            @RequestParam String refName,
            @RequestParam(defaultValue = "Manual administrative freeze") String reason
    ) {
        log.warn("Administrative freeze requested for ref '{}': reason={}", refName, reason);
        adminService.freezeTargetRef(refName, reason);
        return ResponseEntity.ok(Map.of(
                "refName", refName,
                "isFrozen", true,
                "reason", reason
        ));
    }

    /**
     * Legacy unfreeze endpoint - decommissioned.
     * Unilateral unfreezing is disabled to prevent bypass of dual-custody break-glass controls.
     * Returns HTTP 410 Gone and never invokes the unfreeze service.
     */
    @PostMapping("/refs/unfreeze")
    public ResponseEntity<Map<String, Object>> unfreezeRef(@RequestParam String refName) {
        log.warn("Direct administrative unfreeze attempt rejected for ref '{}': endpoint is decommissioned (HTTP 410 Gone)", refName);
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "status", HttpStatus.GONE.value(),
                "error", "Gone",
                "message", "Direct unfreeze is decommissioned. Frozen reference '" + refName +
                        "' requires the dual-custody break-glass review process to manage.",
                "refName", refName
        ));
    }
}
