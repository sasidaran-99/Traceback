package com.traceback.api.dto;

import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.remediation.RemediationFailureClassification;
import com.traceback.core.remediation.RemediationOperationStatus;
import com.traceback.core.remediation.RemediationOperationType;

import java.time.Instant;
import java.util.UUID;

public record RemediationOperationResponse(
        UUID id,
        UUID investigationId,
        String idempotencyKey,
        String repositoryPath,
        String targetRef,
        String expectedOldSha,
        String proposedNewSha,
        RemediationOperationType operationType,
        RemediationOperationStatus status,
        String workerId,
        Long fencingToken,
        Instant leaseExpiresAt,
        String initiatedBy,
        RemediationFailureClassification failureClassification,
        String failureDetails,
        String recoveryStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public static RemediationOperationResponse fromEntity(RemediationOperationEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RemediationOperationResponse(
                entity.getId(),
                entity.getInvestigationId(),
                entity.getIdempotencyKey(),
                entity.getRepositoryPath(),
                entity.getTargetRef(),
                entity.getExpectedOldSha(),
                entity.getProposedNewSha(),
                entity.getOperationType(),
                entity.getStatus(),
                entity.getWorkerId(),
                entity.getFencingToken(),
                entity.getLeaseExpiresAt(),
                entity.getInitiatedBy(),
                entity.getFailureClassification(),
                entity.getFailureDetails(),
                entity.getRecoveryStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Sanitizes response by redacting internal repository filesystem paths and failure detail paths.
     * Prevents host environment enumeration in discovery and listing APIs.
     * Preserves null values and does not modify the underlying persisted entity.
     */
    public static RemediationOperationResponse fromEntitySanitized(RemediationOperationEntity entity) {
        if (entity == null) {
            return null;
        }
        String sanitizedFailureDetails = com.traceback.api.exception.GlobalExceptionHandler.sanitizeErrorMessage(entity.getFailureDetails());
        return new RemediationOperationResponse(
                entity.getId(),
                entity.getInvestigationId(),
                entity.getIdempotencyKey(),
                null,
                entity.getTargetRef(),
                entity.getExpectedOldSha(),
                entity.getProposedNewSha(),
                entity.getOperationType(),
                entity.getStatus(),
                entity.getWorkerId(),
                entity.getFencingToken(),
                entity.getLeaseExpiresAt(),
                entity.getInitiatedBy(),
                entity.getFailureClassification(),
                sanitizedFailureDetails,
                entity.getRecoveryStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
