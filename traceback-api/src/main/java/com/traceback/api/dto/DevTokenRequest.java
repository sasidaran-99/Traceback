package com.traceback.api.dto;

import com.traceback.api.security.dev.DevPersona;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for obtaining a development-only JWT.
 * Restricts caller input strictly to the predefined persona enum.
 */
public record DevTokenRequest(
        @NotNull(message = "persona is required")
        DevPersona persona
) {
}
