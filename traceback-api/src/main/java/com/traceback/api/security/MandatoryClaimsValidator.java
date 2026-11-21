package com.traceback.api.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Validates the presence, semantic relationships, and validity of mandatory JWT claims:
 * - subject ('sub') must be present and non-blank
 * - expiration ('exp') must be present, valid, and not expired (allowing clock skew)
 * - issued-at ('iat') must be present, valid, and not in the future (allowing clock skew)
 * - not-before ('nbf') must be present, valid, and not in the future (allowing clock skew)
 * - semantic ordering: exp must not precede iat or nbf
 */
public class MandatoryClaimsValidator implements OAuth2TokenValidator<Jwt> {

    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);

    private final Clock clock;
    private final Duration clockSkew;

    public MandatoryClaimsValidator() {
        this(Clock.systemUTC(), DEFAULT_CLOCK_SKEW);
    }

    public MandatoryClaimsValidator(Duration clockSkew) {
        this(Clock.systemUTC(), clockSkew);
    }

    public MandatoryClaimsValidator(Clock clock, Duration clockSkew) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew cannot be null");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // 1. Mandatory subject
        String subject = jwt.getSubject();
        if (subject == null || subject.trim().isEmpty()) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Mandatory 'sub' claim is missing or empty", null)
            );
        }

        // 2. Mandatory exp, iat, nbf timestamps
        Instant exp;
        Instant iat;
        Instant nbf;
        try {
            exp = jwt.getExpiresAt();
            iat = jwt.getIssuedAt();
            nbf = jwt.getNotBefore();
        } catch (Exception e) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Malformed or unparseable timestamp claim: " + e.getMessage(), null)
            );
        }

        if (exp == null) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Mandatory 'exp' claim is missing", null)
            );
        }

        if (iat == null) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Mandatory 'iat' claim is missing", null)
            );
        }

        if (nbf == null) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Mandatory 'nbf' claim is missing", null)
            );
        }

        Instant now = clock.instant();

        // 3. Reject future iat beyond clock skew: iat > now + clockSkew
        if (iat.isAfter(now.plus(clockSkew))) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token issued-at timestamp ('iat'=" + iat + ") is in the future (now=" + now + ", clockSkew=" + clockSkew + ")", null)
            );
        }

        // 4. Validate nbf: token not valid before nbf (allowing clock skew): nbf > now + clockSkew
        if (nbf.isAfter(now.plus(clockSkew))) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token is not valid before " + nbf + " (now=" + now + ", clockSkew=" + clockSkew + ")", null)
            );
        }

        // 5. Expiration validation: exp < now - clockSkew
        if (exp.isBefore(now.minus(clockSkew))) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token expired at " + exp + " (now=" + now + ", clockSkew=" + clockSkew + ")", null)
            );
        }

        // 6. Semantic ordering validation: exp must not precede iat or nbf
        if (exp.isBefore(iat)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid timestamp order: 'exp' (" + exp + ") precedes 'iat' (" + iat + ")", null)
            );
        }

        if (exp.isBefore(nbf)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid timestamp order: 'exp' (" + exp + ") precedes 'nbf' (" + nbf + ")", null)
            );
        }

        return OAuth2TokenValidatorResult.success();
    }

    public Clock getClock() {
        return clock;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }
}
