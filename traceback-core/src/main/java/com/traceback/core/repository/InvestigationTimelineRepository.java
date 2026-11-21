package com.traceback.core.repository;

import com.traceback.core.domain.TimelineEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for durable investigation timeline events.
 */
@Repository
public interface InvestigationTimelineRepository extends JpaRepository<TimelineEventEntity, UUID> {

    List<TimelineEventEntity> findByInvestigationIdOrderByTimestampAsc(UUID investigationId);

    long countByInvestigationId(UUID investigationId);
}
