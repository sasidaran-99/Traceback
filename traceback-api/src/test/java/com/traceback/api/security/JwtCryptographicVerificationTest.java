package com.traceback.api.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtCryptographicVerificationTest {

    private TestJwtKeyProvider keyProvider;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        keyProvider = new TestJwtKeyProvider();

        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withPublicKey(keyProvider.getPublicKey()).build();
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(TestJwtKeyProvider.DEFAULT_ISSUER);
        OAuth2TokenValidator<Jwt> combinedValidator = new DelegatingOAuth2TokenValidator<>(
                withIssuer,
                new AudienceValidator(TestJwtKeyProvider.DEFAULT_AUDIENCE),
                new MandatoryClaimsValidator()
        );
        nimbusDecoder.setJwtValidator(combinedValidator);
        this.decoder = nimbusDecoder;
    }

    @Test
    @DisplayName("Valid cryptographically signed RSA JWT decodes successfully")
    void validJwtDecodes() {
        String token = keyProvider.builder()
                .subject("alice-admin")
                .roles(List.of("ADMIN"))
                .build();

        Jwt jwt = decoder.decode(token);
        assertNotNull(jwt);
        assertEquals("alice-admin", jwt.getSubject());
        assertEquals(TestJwtKeyProvider.DEFAULT_ISSUER, jwt.getIssuer().toString());
        assertEquals(List.of(TestJwtKeyProvider.DEFAULT_AUDIENCE), jwt.getAudience());
    }

    @Test
    @DisplayName("JWT signed with an untrusted/different RSA key is rejected (invalid signature)")
    void invalidSignatureRejected() {
        String forgedToken = keyProvider.builder()
                .subject("attacker")
                .roles(List.of("ADMIN"))
                .signWithInvalidKey()
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(forgedToken));
    }

    @Test
    @DisplayName("Expired JWT is rejected by expiration validator")
    void expiredJwtRejected() {
        String expiredToken = keyProvider.builder()
                .subject("bob")
                .issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(expiredToken));
    }

    @Test
    @DisplayName("JWT with wrong issuer is rejected by issuer validator")
    void wrongIssuerRejected() {
        String wrongIssuerToken = keyProvider.builder()
                .subject("bob")
                .issuer("https://untrusted-idp.example.com")
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(wrongIssuerToken));
    }

    @Test
    @DisplayName("JWT with wrong audience is rejected by audience validator")
    void wrongAudienceRejected() {
        String wrongAudienceToken = keyProvider.builder()
                .subject("bob")
                .audience("some-other-service")
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(wrongAudienceToken));
    }

    @Test
    @DisplayName("JWT with missing subject ('sub') is rejected by mandatory claims validator")
    void missingSubjectRejected() {
        String missingSubToken = keyProvider.builder()
                .omitSubject()
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(missingSubToken));
    }

    @Test
    @DisplayName("JWT with future not-before timestamp is rejected")
    void futureNotBeforeRejected() {
        String notYetValidToken = keyProvider.builder()
                .subject("bob")
                .notBefore(Instant.now().plusSeconds(600))
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(notYetValidToken));
    }

    @Test
    @DisplayName("JWT with missing expiration ('exp') is rejected by mandatory claims validator")
    void missingExpirationRejected() {
        String missingExpToken = keyProvider.builder()
                .subject("bob")
                .omitExpiresAt()
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(missingExpToken));
    }

    @Test
    @DisplayName("JWT with missing issued-at ('iat') is rejected by mandatory claims validator")
    void missingIssuedAtRejected() {
        String missingIatToken = keyProvider.builder()
                .subject("bob")
                .omitIssuedAt()
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(missingIatToken));
    }

    @Test
    @DisplayName("JWT with missing not-before ('nbf') is rejected by mandatory claims validator")
    void missingNotBeforeRejected() {
        String missingNbfToken = keyProvider.builder()
                .subject("bob")
                .omitNotBefore()
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(missingNbfToken));
    }

    @Test
    @DisplayName("JWT with future issued-at timestamp beyond clock skew is rejected")
    void futureIssuedAtRejected() {
        String futureIatToken = keyProvider.builder()
                .subject("bob")
                .issuedAt(Instant.now().plusSeconds(600))
                .build();

        assertThrows(JwtException.class, () -> decoder.decode(futureIatToken));
    }

    @Test
    @DisplayName("Unsigned token with alg: none is rejected by decoder")
    void unsupportedAlgNoneTokenRejected() {
        String noneToken = keyProvider.createUnsignedToken("bob", List.of("OPERATOR"));
        assertThrows(JwtException.class, () -> decoder.decode(noneToken));
    }

    @Test
    @DisplayName("Symmetric HMAC token (HS256) is rejected by asymmetric RSA decoder")
    void unsupportedHmacTokenRejected() {
        byte[] secret = "secret-key-at-least-256-bits-long-32-bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hmacToken = keyProvider.createHmacToken("bob", List.of("OPERATOR"), secret);
        assertThrows(JwtException.class, () -> decoder.decode(hmacToken));
    }

    @Test
    @DisplayName("Deterministic Clock test verifies clock skew acceptance and boundary rejection")
    void deterministicClockSkewTest() {
        Instant baseTime = Instant.parse("2026-10-10T12:00:00Z");
        java.time.Clock fixedClock = java.time.Clock.fixed(baseTime, java.time.ZoneOffset.UTC);
        MandatoryClaimsValidator validator = new MandatoryClaimsValidator(fixedClock, java.time.Duration.ofSeconds(60));

        // Token within 30s in future (tolerated by 60s skew)
        Jwt withinSkew = Jwt.withTokenValue("mock")
                .header("alg", "RS256")
                .subject("bob")
                .issuedAt(baseTime.plusSeconds(30))
                .notBefore(baseTime.plusSeconds(30))
                .expiresAt(baseTime.plusSeconds(3600))
                .build();
        org.springframework.security.oauth2.core.OAuth2TokenValidatorResult res1 = validator.validate(withinSkew);
        org.junit.jupiter.api.Assertions.assertFalse(res1.hasErrors(), "Timestamp within skew must be accepted");

        // Token with iat 90s in future (exceeds 60s skew)
        Jwt exceedsSkew = Jwt.withTokenValue("mock")
                .header("alg", "RS256")
                .subject("bob")
                .issuedAt(baseTime.plusSeconds(90))
                .notBefore(baseTime.minusSeconds(10))
                .expiresAt(baseTime.plusSeconds(3600))
                .build();
        org.springframework.security.oauth2.core.OAuth2TokenValidatorResult res2 = validator.validate(exceedsSkew);
        org.junit.jupiter.api.Assertions.assertTrue(res2.hasErrors(), "Future iat exceeding skew must be rejected");

        // Expired token beyond 60s skew (exp = now - 100s, iat = now - 200s)
        Jwt expired = Jwt.withTokenValue("mock")
                .header("alg", "RS256")
                .subject("bob")
                .issuedAt(baseTime.minusSeconds(200))
                .notBefore(baseTime.minusSeconds(200))
                .expiresAt(baseTime.minusSeconds(100))
                .build();
        org.springframework.security.oauth2.core.OAuth2TokenValidatorResult res3 = validator.validate(expired);
        org.junit.jupiter.api.Assertions.assertTrue(res3.hasErrors(), "Expired token exceeding skew must be rejected");
    }
}
