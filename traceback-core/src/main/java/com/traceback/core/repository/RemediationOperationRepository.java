package com.traceback.core.repository;

import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.remediation.RemediationOperationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RemediationOperationRepository extends JpaRepository<RemediationOperationEntity, UUID> {

    Optional<RemediationOperationEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<RemediationOperationEntity> findByInvestigationIdAndIdempotencyKey(UUID investigationId, String idempotencyKey);

    List<RemediationOperationEntity> findByTargetRefOrderByCreatedAtDesc(String targetRef);

    List<RemediationOperationEntity> findByInvestigationIdOrderByCreatedAtDesc(UUID investigationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM RemediationOperationEntity o WHERE o.id = :id")
    Optional<RemediationOperationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT o FROM RemediationOperationEntity o WHERE o.status IN :statuses AND o.leaseExpiresAt IS NOT NULL AND o.leaseExpiresAt < :now")
    List<RemediationOperationEntity> findExpiredLeaseOperations(
            @Param("statuses") Collection<RemediationOperationStatus> statuses,
            @Param("now") Instant now
    );

    @Query("SELECT o FROM RemediationOperationEntity o WHERE o.status = :status AND o.createdAt < :cutoff")
    List<RemediationOperationEntity> findStaleProposals(
            @Param("status") RemediationOperationStatus status,
            @Param("cutoff") Instant cutoff
    );
}
