package com.traceback.core.repository;

import com.traceback.core.domain.AgentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for durable investigation agent audit trail steps.
 */
@Repository
public interface InvestigationAgentStepRepository extends JpaRepository<AgentStepEntity, UUID> {

    List<AgentStepEntity> findByInvestigationIdOrderByStepNumberAsc(UUID investigationId);

    Optional<AgentStepEntity> findByInvestigationIdAndStepNumber(UUID investigationId, int stepNumber);

    long countByInvestigationId(UUID investigationId);

    void deleteByInvestigationId(UUID investigationId);

    void deleteByInvestigationIdAndStepNumberGreaterThan(UUID investigationId, int stepNumber);
}
