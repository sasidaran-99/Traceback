package com.traceback.core.repository;

import com.traceback.core.domain.InvestigationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for durable investigations.
 */
@Repository
public interface InvestigationRepository extends JpaRepository<InvestigationEntity, UUID> {

    List<InvestigationEntity> findAllByOrderByCreatedAtDesc();

    Optional<InvestigationEntity> findByReportId(String reportId);
}
