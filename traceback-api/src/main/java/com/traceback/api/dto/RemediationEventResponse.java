package com.traceback.api.dto;

import com.traceback.core.domain.RemediationOperationEventEntity;
import com.traceback.core.remediation.RemediationOperationEventType;
import com.traceback.core.remediation.RemediationOperationStatus;

import java.time.Instant;
import java.util.UUID;

public record RemediationEventResponse(
        UUID id,
        UUID operationId,
        Long fencingToken,
        RemediationOperationEventType eventType,
        RemediationOperationStatus fromStatus,
        RemediationOperationStatus toStatus,
        String refName,
        String expectedOldSha,
        String resultSha,
        String jgitResult,
        String details,
        Instant createdAt
) {
    public static RemediationEventResponse fromEntity(RemediationOperationEventEntity entity) {
        return new RemediationEventResponse(
                entity.getId(),
                entity.getOperationId(),
                entity.getFencingToken(),
                entity.getEventType(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getRefName(),
                entity.getExpectedOldSha(),
                entity.getResultSha(),
                entity.getJgitResult(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }
}
