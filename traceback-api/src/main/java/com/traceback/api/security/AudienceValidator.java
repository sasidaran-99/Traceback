package com.traceback.api.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Objects;

/**
 * Validates that the JWT contains the required audience.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;
    private final OAuth2Error error;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience cannot be null");
        this.error = new OAuth2Error("invalid_token", "The required audience '" + expectedAudience + "' is missing", null);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audiences = jwt.getAudience();
        if (audiences != null && audiences.contains(this.expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(this.error);
    }
}
