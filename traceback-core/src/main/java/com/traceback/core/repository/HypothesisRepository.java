package com.traceback.core.repository;

import com.traceback.core.domain.Hypothesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for candidate root-cause hypotheses.
 */
@Repository
public interface HypothesisRepository extends JpaRepository<Hypothesis, UUID> {

    /**
     * Finds hypotheses associated with an incident ordered by calculated rank.
     */
    List<Hypothesis> findByIncidentIdOrderByRankAsc(UUID incidentId);
}
