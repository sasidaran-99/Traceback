package com.traceback.api.dto;

import com.traceback.core.domain.RemediationBreakGlassRequestEntity;
import com.traceback.core.remediation.RemediationBreakGlassDecision;
import com.traceback.core.remediation.RemediationBreakGlassStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO representing an administrative break-glass review request.
 * Deliberately redacts internal server and infrastructure details including
 * absolute repository filesystem paths, credentials, and host network information.
 */
public record RemediationBreakGlassResponse(
        UUID id,
        UUID operationId,
        String targetRef,
        String expectedOldSha,
        String proposedNewSha,
        String snapshotGitSha,
        Long operationFencingToken,
        RemediationBreakGlassDecision requestedDecision,
        RemediationBreakGlassStatus status,
        String rationale,
        String evidenceDigest,
        String evidenceBundleRef,
        String evidenceBundleVersion,
        String initiatorIssuer,
        String initiatorSubject,
        Instant createdAt,
        Instant expiresAt,
        String approverIssuer,
        String approverSubject,
        Instant resolvedAt,
        String rejectionReason,
        String executionNotes,
        String cancelledByIssuer,
        String cancelledBySubject,
        Instant cancelledAt,
        String cancellationReason
) {
    public static RemediationBreakGlassResponse fromEntity(RemediationBreakGlassRequestEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RemediationBreakGlassResponse(
                entity.getId(),
                entity.getOperationId(),
                entity.getTargetRef(),
                entity.getExpectedOldSha(),
                entity.getProposedNewSha(),
                entity.getSnapshotGitSha(),
                entity.getOperationFencingToken(),
                entity.getRequestedDecision(),
                entity.getStatus(),
                entity.getRationale(),
                entity.getEvidenceDigest(),
                entity.getEvidenceBundleRef(),
                entity.getEvidenceBundleVersion(),
                entity.getInitiatorIssuer(),
                entity.getInitiatorSubject(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getApproverIssuer(),
                entity.getApproverSubject(),
                entity.getResolvedAt(),
                entity.getRejectionReason(),
                entity.getExecutionNotes(),
                entity.getCancelledByIssuer(),
                entity.getCancelledBySubject(),
                entity.getCancelledAt(),
                entity.getCancellationReason()
        );
    }
}
