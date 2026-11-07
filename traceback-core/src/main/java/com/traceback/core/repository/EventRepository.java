package com.traceback.core.repository;

import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for normalized events.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    /**
     * Finds events for a specific service and environment within an incident window.
     */
    List<Event> findByServiceNameAndEnvironmentAndTimestampBetweenOrderByTimestampDesc(
            String serviceName, String environment, Instant start, Instant end);

    /**
     * Finds events by external entity ID (e.g., commit SHA, build ID, deployment ID).
     */
    List<Event> findByEntityId(String entityId);

    /**
     * Finds events by event type.
     */
    List<Event> findByEventType(EventType eventType);

    /**
     * Finds events by service name.
     */
    List<Event> findByServiceName(String serviceName);
}
