package com.traceback.ingestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.Event;
import com.traceback.core.repository.EventRepository;
import com.traceback.ingestion.dto.EventIngestionRequest;
import com.traceback.ingestion.dto.EventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for validating, normalizing, and persisting ingested events.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public EventIngestionService(EventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Ingests a new event into the Traceback platform.
     *
     * @param request event ingestion payload
     * @return persisted event response
     */
    @Transactional
    public EventResponse ingestEvent(EventIngestionRequest request) {
        String metadataJson = null;
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.metadata());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize event metadata for entityId: {}", request.entityId(), e);
                throw new IllegalArgumentException("Invalid metadata structure: " + e.getMessage(), e);
            }
        }

        Event event = new Event(
                UUID.randomUUID(),
                request.eventType(),
                request.source(),
                request.serviceName().trim(),
                request.environment().trim().toLowerCase(),
                request.timestamp(),
                request.entityId().trim(),
                metadataJson
        );

        Event savedEvent = eventRepository.save(event);
        log.info("Ingested event: id={}, type={}, entityId={}, service={}",
                savedEvent.getId(), savedEvent.getEventType(), savedEvent.getEntityId(), savedEvent.getServiceName());

        return EventResponse.fromEntity(savedEvent);
    }

    /**
     * Retrieves an event by its UUID identifier.
     *
     * @param eventId unique event ID
     * @return optional event response
     */
    @Transactional(readOnly = true)
    public Optional<EventResponse> getEventById(UUID eventId) {
        return eventRepository.findById(eventId).map(EventResponse::fromEntity);
    }
}
