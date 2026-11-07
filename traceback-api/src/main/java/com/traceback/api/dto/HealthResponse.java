package com.traceback.api.dto;

import java.time.Instant;

/**
 * Health check response payload.
 */
public record HealthResponse(
        String status,
        Instant timestamp,
        String service,
        String version
) {
    public static HealthResponse up() {
        return new HealthResponse("UP", Instant.now(), "traceback-api", "1.0.0-SNAPSHOT");
    }
}
