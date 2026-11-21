package com.traceback.api.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test configuration providing the primary JwtDecoder backed by the ephemeral
 * in-memory TestJwtKeyProvider. Ensures all tests verify genuine RSA signatures
 * without making network calls to external identity providers.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public TestJwtKeyProvider testJwtKeyProvider() {
        return new TestJwtKeyProvider();
    }

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder(TestJwtKeyProvider keyProvider) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyProvider.getPublicKey()).build();

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(TestJwtKeyProvider.DEFAULT_ISSUER);
        OAuth2TokenValidator<Jwt> combinedValidator = new DelegatingOAuth2TokenValidator<>(
                withIssuer,
                new AudienceValidator(TestJwtKeyProvider.DEFAULT_AUDIENCE),
                new MandatoryClaimsValidator()
        );
        decoder.setJwtValidator(combinedValidator);
        return decoder;
    }
}
