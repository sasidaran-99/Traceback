package com.traceback.api.security.dev;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.traceback.api.dto.DevTokenRequest;
import com.traceback.api.dto.DevTokenResponse;
import com.traceback.api.security.JwtSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Service generating genuine RS256-signed JWTs for predefined development personas.
 * Available only in dev and local profiles when explicitly enabled via configuration.
 */
@Service
@Profile({"dev", "local"})
@ConditionalOnProperty(prefix = "traceback.security.dev-auth", name = "enabled", havingValue = "true")
public class DevTokenService {

    private static final Logger log = LoggerFactory.getLogger(DevTokenService.class);
    public static final Duration TOKEN_EXPIRATION = Duration.ofMinutes(15);

    private final DevKeyManager devKeyManager;
    private final JwtSecurityProperties properties;

    public DevTokenService(DevKeyManager devKeyManager, JwtSecurityProperties properties) {
        this.devKeyManager = Objects.requireNonNull(devKeyManager, "devKeyManager cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    public DevTokenResponse issueToken(DevTokenRequest request) {
        if (request == null || request.persona() == null) {
            throw new IllegalArgumentException("persona is required");
        }

        DevPersona persona = request.persona();
        String issuer = properties.getIssuer() != null && !properties.getIssuer().isBlank()
                ? properties.getIssuer()
                : "https://auth.traceback.dev";
        String audience = properties.getAudience() != null && !properties.getAudience().isBlank()
                ? properties.getAudience()
                : "traceback-api";

        Instant now = Instant.now();
        Instant issuedAt = now.minusSeconds(1);
        Instant notBefore = issuedAt;
        Instant expiresAt = now.plus(TOKEN_EXPIRATION);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(persona.getSubject())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(notBefore))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", persona.getRoles())
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).build();
        SignedJWT signedJWT = new SignedJWT(header, claimsSet);

        try {
            RSASSASigner signer = new RSASSASigner(devKeyManager.getPrivateKey());
            signedJWT.sign(signer);
        } catch (Exception e) {
            log.error("Failed to sign development JWT for persona {}", persona, e);
            throw new IllegalStateException("Failed to cryptographically sign development token", e);
        }

        log.info("Issued development JWT for persona '{}' (subject='{}', roles={}, exp={})",
                persona.name(), persona.getSubject(), persona.getRoles(), expiresAt);

        return new DevTokenResponse(
                signedJWT.serialize(),
                "Bearer",
                TOKEN_EXPIRATION.toSeconds(),
                issuer,
                persona.getSubject(),
                persona.getRoles()
        );
    }
}
