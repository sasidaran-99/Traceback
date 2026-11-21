package com.traceback.core.repository;

import com.traceback.core.domain.ClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for durable investigation claims with investigation-scoped lookups.
 */
@Repository
public interface InvestigationClaimRepository extends JpaRepository<ClaimEntity, UUID> {

    List<ClaimEntity> findByInvestigationId(UUID investigationId);

    List<ClaimEntity> findByInvestigationIdAndClaimType(UUID investigationId, String claimType);

    Optional<ClaimEntity> findByInvestigationIdAndClaimId(UUID investigationId, String claimId);

    boolean existsByInvestigationIdAndClaimId(UUID investigationId, String claimId);

    Optional<ClaimEntity> findFirstByClaimId(String claimId);

    default Optional<ClaimEntity> findByClaimId(String claimId) {
        return findFirstByClaimId(claimId);
    }

    long countByInvestigationId(UUID investigationId);
}
