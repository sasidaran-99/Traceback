package com.traceback.api.controller;

import com.traceback.api.dto.BreakGlassFinalizationResponse;
import com.traceback.api.dto.CancelBreakGlassRequest;
import com.traceback.api.dto.FinalizeBreakGlassRequest;
import com.traceback.api.dto.ProposeBreakGlassRequest;
import com.traceback.api.dto.RejectBreakGlassRequest;
import com.traceback.api.dto.RemediationBreakGlassResponse;
import com.traceback.core.domain.RemediationBreakGlassRequestEntity;
import com.traceback.core.remediation.BreakGlassAdminIdentity;
import com.traceback.core.remediation.BreakGlassFinalizationOutcome;
import com.traceback.core.remediation.CancelBreakGlassCommand;
import com.traceback.core.remediation.FinalizeBreakGlassCommand;
import com.traceback.core.remediation.ProposeBreakGlassCommand;
import com.traceback.core.remediation.RejectBreakGlassCommand;
import com.traceback.core.remediation.RemediationBreakGlassDecision;
import com.traceback.core.remediation.RemediationBreakGlassService;
import com.traceback.core.security.RemediationAuthenticationException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Dedicated REST controller for dual-custody break-glass recovery reviews.
 * Governs four mutation endpoints (ROLE_ADMIN only) and two read endpoints
 * (ROLE_ADMIN, ROLE_OPERATOR, ROLE_VIEWER).
 * Administrator identity is strictly server-derived from verified JWT claims (iss, sub).
 * The controller does NOT use @Transactional; transaction scoping and multi-level
 * lock management are exclusively delegated to RemediationBreakGlassService.
 */
@RestController
@RequestMapping("/api/remediations/{operationId}/break-glass")
public class RemediationBreakGlassController {

    private static final Logger log = LoggerFactory.getLogger(RemediationBreakGlassController.class);

    private final RemediationBreakGlassService breakGlassService;

    public RemediationBreakGlassController(RemediationBreakGlassService breakGlassService) {
        this.breakGlassService = Objects.requireNonNull(breakGlassService, "breakGlassService cannot be null");
    }

    /**
     * Proposes a new dual-custody break-glass review request for an operation in RECOVERY_REQUIRED.
     */
    @PostMapping("/proposals")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RemediationBreakGlassResponse> proposeRequest(
            @PathVariable UUID operationId,
            @Valid @RequestBody ProposeBreakGlassRequest request,
            Authentication authentication
    ) {
        BreakGlassAdminIdentity initiator = extractAdminIdentity(authentication);
        log.info("Proposing break-glass review for operation {}: initiator={}:{}",
                operationId, initiator.issuer(), initiator.subject());

        ProposeBreakGlassCommand command = new ProposeBreakGlassCommand(
                operationId,
                request.rationale(),
                request.normalizedEvidenceDigest(),
                request.trimmedEvidenceBundleRef(),
                request.trimmedEvidenceBundleVersion()
        );

        RemediationBreakGlassRequestEntity created = breakGlassService.proposeRequest(command, initiator);
        URI location = URI.create("/api/remediations/" + operationId + "/break-glass/" + created.getId());
        return ResponseEntity.created(location).body(RemediationBreakGlassResponse.fromEntity(created));
    }

    /**
     * Finalizes a pending break-glass request with the KEEP_FROZEN decision by a second administrator.
     * Enforces anti-self-approval, expiry checks, and state drift validation.
     * KEEP_FROZEN confirms retention of the target ref freeze; no Git recovery is executed.
     */
    @PostMapping("/{requestId}/finalize")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BreakGlassFinalizationResponse> finalizeKeepFrozen(
            @PathVariable UUID operationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody FinalizeBreakGlassRequest request,
            Authentication authentication
    ) {
        if (request.decision() != RemediationBreakGlassDecision.KEEP_FROZEN) {
            throw new IllegalArgumentException("Only KEEP_FROZEN decision is accepted");
        }

        BreakGlassAdminIdentity approver = extractAdminIdentity(authentication);
        log.info("Finalizing break-glass review {} for operation {}: decision={}, approver={}:{}",
                requestId, operationId, request.decision(), approver.issuer(), approver.subject());

        FinalizeBreakGlassCommand command = new FinalizeBreakGlassCommand(
                operationId,
                requestId,
                request.trimmedExecutionNotes()
        );

        BreakGlassFinalizationOutcome outcome = breakGlassService.finalizeKeepFrozen(command, approver);
        return ResponseEntity.ok(BreakGlassFinalizationResponse.fromOutcome(outcome));
    }

    /**
     * Rejects a pending break-glass request by a second administrator.
     * Enforces anti-self-approval and sets status to REJECTED.
     */
    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RemediationBreakGlassResponse> rejectRequest(
            @PathVariable UUID operationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody RejectBreakGlassRequest request,
            Authentication authentication
    ) {
        BreakGlassAdminIdentity rejecter = extractAdminIdentity(authentication);
        log.info("Rejecting break-glass review {} for operation {}: rejecter={}:{}",
                requestId, operationId, rejecter.issuer(), rejecter.subject());

        RejectBreakGlassCommand command = new RejectBreakGlassCommand(
                operationId,
                requestId,
                request.trimmedRejectionReason()
        );

        RemediationBreakGlassRequestEntity rejected = breakGlassService.rejectRequest(command, rejecter);
        return ResponseEntity.ok(RemediationBreakGlassResponse.fromEntity(rejected));
    }

    /**
     * Cancels a pending break-glass request. Can only be performed by the proposal initiator.
     */
    @PostMapping("/{requestId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RemediationBreakGlassResponse> cancelRequest(
            @PathVariable UUID operationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody CancelBreakGlassRequest request,
            Authentication authentication
    ) {
        BreakGlassAdminIdentity initiator = extractAdminIdentity(authentication);
        log.info("Cancelling break-glass review {} for operation {}: initiator={}:{}",
                requestId, operationId, initiator.issuer(), initiator.subject());

        CancelBreakGlassCommand command = new CancelBreakGlassCommand(
                operationId,
                requestId,
                request.trimmedCancellationReason()
        );

        RemediationBreakGlassRequestEntity cancelled = breakGlassService.cancelRequest(command, initiator);
        return ResponseEntity.ok(RemediationBreakGlassResponse.fromEntity(cancelled));
    }

    /**
     * Retrieves a single break-glass request by ID within an operation scope.
     */
    @GetMapping("/{requestId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<RemediationBreakGlassResponse> getRequest(
            @PathVariable UUID operationId,
            @PathVariable UUID requestId
    ) {
        RemediationBreakGlassRequestEntity entity = breakGlassService.getRequest(operationId, requestId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Break-glass request not found for id " + requestId + " and operation " + operationId
                ));
        return ResponseEntity.ok(RemediationBreakGlassResponse.fromEntity(entity));
    }

    /**
     * Lists all break-glass requests for an operation in descending chronological order.
     */
    @GetMapping({"", "/"})
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<RemediationBreakGlassResponse>> listRequests(
            @PathVariable UUID operationId
    ) {
        List<RemediationBreakGlassRequestEntity> list = breakGlassService.getRequestsForOperation(operationId);
        List<RemediationBreakGlassResponse> response = list.stream()
                .map(RemediationBreakGlassResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Extracts and validates the administrative actor identity exclusively from verified JWT claims.
     * Both 'iss' and 'sub' must be present and non-blank.
     * Never trusts request bodies, query parameters, or client-supplied headers.
     */
    private BreakGlassAdminIdentity extractAdminIdentity(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RemediationAuthenticationException("Authentication required: No authenticated principal found");
        }

        Jwt jwt = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            jwt = jwtAuth.getToken();
        } else if (authentication.getPrincipal() instanceof Jwt principalJwt) {
            jwt = principalJwt;
        }

        if (jwt == null) {
            throw new RemediationAuthenticationException("Authentication required: Token is not a valid JWT");
        }

        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null && jwt.getIssuer() != null) {
            issuer = jwt.getIssuer().toString();
        }
        String subject = jwt.getSubject();
        if (subject == null) {
            subject = jwt.getClaimAsString("sub");
        }

        if (issuer == null || issuer.trim().isEmpty()) {
            throw new RemediationAuthenticationException("Authentication failed: Missing or blank 'iss' claim in JWT");
        }
        if (subject == null || subject.trim().isEmpty()) {
            throw new RemediationAuthenticationException("Authentication failed: Missing or blank 'sub' claim in JWT");
        }

        return new BreakGlassAdminIdentity(issuer.trim(), subject.trim());
    }
}
