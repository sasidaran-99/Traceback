package com.traceback.ingestion.dto;

import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard API response representation of an ingested event.
 */
public record EventResponse(
        UUID id,
        EventType eventType,
        EventSource source,
        String serviceName,
        String environment,
        Instant timestamp,
        String entityId,
        String metadata,
        Instant createdAt
) {
    public static EventResponse fromEntity(Event entity) {
        return new EventResponse(
                entity.getId(),
                entity.getEventType(),
                entity.getSource(),
                entity.getServiceName(),
                entity.getEnvironment(),
                entity.getTimestamp(),
                entity.getEntityId(),
                entity.getMetadata(),
                entity.getCreatedAt()
        );
    }
}
