package com.traceback.ingestion.controller;

import com.traceback.ingestion.dto.EventIngestionRequest;
import com.traceback.ingestion.dto.EventResponse;
import com.traceback.ingestion.service.EventIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for ingesting events into the Traceback platform.
 */
@RestController
@RequestMapping("/api/events")
public class EventIngestionController {

    private final EventIngestionService ingestionService;

    public EventIngestionController(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Ingests a new event.
     *
     * @param request validated event ingestion payload
     * @return 201 Created with URI and persisted event details
     */
    @PostMapping
    public ResponseEntity<EventResponse> ingestEvent(@Valid @RequestBody EventIngestionRequest request) {
        EventResponse response = ingestionService.ingestEvent(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /**
     * Retrieves an ingested event by ID.
     *
     * @param id event UUID
     * @return 200 OK or 404 Not Found
     */
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable UUID id) {
        return ingestionService.getEventById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
