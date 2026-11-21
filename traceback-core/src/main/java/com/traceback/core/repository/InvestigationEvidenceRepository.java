package com.traceback.core.repository;

import com.traceback.core.domain.EvidenceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for durable investigation evidence items with investigation-scoped lookups.
 */
@Repository
public interface InvestigationEvidenceRepository extends JpaRepository<EvidenceItemEntity, UUID> {

    List<EvidenceItemEntity> findByInvestigationId(UUID investigationId);

    Optional<EvidenceItemEntity> findByInvestigationIdAndEvidenceId(UUID investigationId, String evidenceId);

    boolean existsByInvestigationIdAndEvidenceId(UUID investigationId, String evidenceId);

    List<EvidenceItemEntity> findByInvestigationIdAndEvidenceIdIn(UUID investigationId, Collection<String> evidenceIds);

    @Deprecated(since = "9.8.4")
    Optional<EvidenceItemEntity> findFirstByEvidenceId(String evidenceId);

    @Deprecated(since = "9.8.4")
    default Optional<EvidenceItemEntity> findByEvidenceId(String evidenceId) {
        return findFirstByEvidenceId(evidenceId);
    }

    @Deprecated(since = "9.8.4")
    boolean existsByEvidenceId(String evidenceId);

    long countByInvestigationId(UUID investigationId);
}
