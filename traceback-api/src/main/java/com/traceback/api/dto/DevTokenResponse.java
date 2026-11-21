package com.traceback.api.dto;

import java.util.List;

/**
 * Standard bearer token response for development authentication.
 */
public record DevTokenResponse(
        String token,
        String tokenType,
        long expiresIn,
        String issuer,
        String subject,
        List<String> roles
) {
}
