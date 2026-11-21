package com.traceback.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Health check and readiness response payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse(
        String status,
        Instant timestamp,
        String service,
        String version,
        String details
) {
    public HealthResponse(String status, Instant timestamp, String service, String version) {
        this(status, timestamp, service, version, null);
    }

    public static HealthResponse up() {
        return new HealthResponse("UP", Instant.now(), "traceback-api", "1.0.0-SNAPSHOT", null);
    }

    public static HealthResponse ready(String status) {
        return new HealthResponse(status != null ? status : "UP", Instant.now(), "traceback-api", "1.0.0-SNAPSHOT", null);
    }

    public static HealthResponse notReady(String status, String details) {
        return new HealthResponse(status != null ? status : "DOWN", Instant.now(), "traceback-api", "1.0.0-SNAPSHOT", details);
    }
}
