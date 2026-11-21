package com.traceback.core.repository;

import com.traceback.core.domain.RemediationOperationEventEntity;
import com.traceback.core.remediation.RemediationOperationEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RemediationOperationEventRepository extends JpaRepository<RemediationOperationEventEntity, UUID> {

    List<RemediationOperationEventEntity> findByOperationIdOrderByCreatedAtAsc(UUID operationId);

    boolean existsByOperationIdAndEventType(UUID operationId, RemediationOperationEventType eventType);

    Optional<RemediationOperationEventEntity> findTopByOperationIdAndEventTypeOrderByCreatedAtDesc(
            UUID operationId,
            RemediationOperationEventType eventType
    );
}
