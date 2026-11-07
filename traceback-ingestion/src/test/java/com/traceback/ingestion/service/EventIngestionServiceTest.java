package com.traceback.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import com.traceback.core.repository.EventRepository;
import com.traceback.ingestion.dto.EventIngestionRequest;
import com.traceback.ingestion.dto.EventResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventIngestionServiceTest {

    @Mock
    private EventRepository eventRepository;

    private ObjectMapper objectMapper;
    private EventIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ingestionService = new EventIngestionService(eventRepository, objectMapper);
    }

    @Test
    @DisplayName("Should successfully ingest and persist an event with metadata")
    void shouldSuccessfullyIngestEventWithMetadata() {
        Instant timestamp = Instant.parse("2026-10-10T14:10:00Z");
        Map<String, Object> metadata = Map.of(
                "author", "alice@example.com",
                "commitMessage", "Refactor fee math"
        );

        EventIngestionRequest request = new EventIngestionRequest(
                EventType.COMMIT,
                EventSource.GITHUB,
                "payment-service",
                "production",
                timestamp,
                "c1a89b3",
                metadata
        );

        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event e = invocation.getArgument(0);
            e.setCreatedAt(Instant.now());
            return e;
        });

        EventResponse response = ingestionService.ingestEvent(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.eventType()).isEqualTo(EventType.COMMIT);
        assertThat(response.source()).isEqualTo(EventSource.GITHUB);
        assertThat(response.serviceName()).isEqualTo("payment-service");
        assertThat(response.environment()).isEqualTo("production");
        assertThat(response.entityId()).isEqualTo("c1a89b3");
        assertThat(response.metadata()).contains("alice@example.com");

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(eventCaptor.capture());
        Event saved = eventCaptor.getValue();
        assertThat(saved.getEntityId()).isEqualTo("c1a89b3");
    }

    @Test
    @DisplayName("Should retrieve event by ID")
    void shouldRetrieveEventById() {
        UUID id = UUID.randomUUID();
        Event event = new Event(id, EventType.CI_BUILD, EventSource.GITHUB_ACTIONS, "payment-service", "staging", Instant.now(), "build-4011", null);
        event.setCreatedAt(Instant.now());

        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        Optional<EventResponse> result = ingestionService.getEventById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        assertThat(result.get().entityId()).isEqualTo("build-4011");
    }
}
