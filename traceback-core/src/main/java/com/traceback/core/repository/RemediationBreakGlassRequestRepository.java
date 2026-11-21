package com.traceback.core.repository;

import com.traceback.core.domain.RemediationBreakGlassRequestEntity;
import com.traceback.core.remediation.RemediationBreakGlassStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for managing durable dual-custody break-glass recovery requests.
 */
@Repository
public interface RemediationBreakGlassRequestRepository extends JpaRepository<RemediationBreakGlassRequestEntity, UUID> {

    /**
     * Retrieves a break-glass request by ID with a pessimistic write lock (SELECT FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RemediationBreakGlassRequestEntity r WHERE r.id = :id")
    Optional<RemediationBreakGlassRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Retrieves a break-glass request by ID and owning operation ID with a pessimistic write lock (SELECT FOR UPDATE).
     * Prevents cross-operation request exposure or manipulation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RemediationBreakGlassRequestEntity r WHERE r.id = :id AND r.operationId = :operationId")
    Optional<RemediationBreakGlassRequestEntity> findByIdAndOperationIdForUpdate(
            @Param("id") UUID id,
            @Param("operationId") UUID operationId
    );

    /**
     * Finds the active pending break-glass request for a specific operation, if one exists.
     */
    Optional<RemediationBreakGlassRequestEntity> findByOperationIdAndStatus(UUID operationId, RemediationBreakGlassStatus status);

    /**
     * Checks if a break-glass request in the given status exists for an operation.
     */
    boolean existsByOperationIdAndStatus(UUID operationId, RemediationBreakGlassStatus status);

    /**
     * Lists all break-glass requests associated with an operation ordered chronologically descending.
     */
    List<RemediationBreakGlassRequestEntity> findByOperationIdOrderByCreatedAtDesc(UUID operationId);
}
