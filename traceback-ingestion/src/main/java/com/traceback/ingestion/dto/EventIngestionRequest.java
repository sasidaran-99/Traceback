package com.traceback.ingestion.dto;

import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * Ingestion payload DTO for registering events in Traceback.
 */
public record EventIngestionRequest(
        @NotNull(message = "eventType is required")
        EventType eventType,

        @NotNull(message = "source is required")
        EventSource source,

        @NotBlank(message = "serviceName cannot be blank")
        @Size(max = 128, message = "serviceName must not exceed 128 characters")
        String serviceName,

        @NotBlank(message = "environment cannot be blank")
        @Size(max = 64, message = "environment must not exceed 64 characters")
        String environment,

        @NotNull(message = "timestamp is required")
        Instant timestamp,

        @NotBlank(message = "entityId cannot be blank")
        @Size(max = 256, message = "entityId must not exceed 256 characters")
        String entityId,

        Map<String, Object> metadata
) {
}
