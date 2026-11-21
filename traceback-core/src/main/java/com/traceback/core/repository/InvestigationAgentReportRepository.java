package com.traceback.core.repository;

import com.traceback.core.domain.AgentReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for durable investigation agent reports.
 * Supports multiple historical reports per investigation with deterministic ordering.
 */
@Repository
public interface InvestigationAgentReportRepository extends JpaRepository<AgentReportEntity, String> {

    List<AgentReportEntity> findByInvestigationId(UUID investigationId);

    List<AgentReportEntity> findByInvestigationIdOrderByCreatedAtDesc(UUID investigationId);

    Optional<AgentReportEntity> findByReportId(String reportId);

    default Optional<AgentReportEntity> findLatestByInvestigationId(UUID investigationId) {
        List<AgentReportEntity> reports = findByInvestigationIdOrderByCreatedAtDesc(investigationId);
        return reports.isEmpty() ? Optional.empty() : Optional.of(reports.getFirst());
    }
}

