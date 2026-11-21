package com.traceback.core.repository;

import com.traceback.core.domain.InvestigationHypothesisEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for durable, investigation-scoped hypotheses.
 */
@Repository
public interface InvestigationHypothesisRepository extends JpaRepository<InvestigationHypothesisEntity, UUID> {

    List<InvestigationHypothesisEntity> findByInvestigationIdOrderByRankAsc(UUID investigationId);

    Optional<InvestigationHypothesisEntity> findByInvestigationIdAndHypothesisId(UUID investigationId, UUID hypothesisId);

    void deleteByInvestigationId(UUID investigationId);

    long countByInvestigationId(UUID investigationId);
}
