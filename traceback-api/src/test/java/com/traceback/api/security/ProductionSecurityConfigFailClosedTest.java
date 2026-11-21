package com.traceback.api.security;

import com.traceback.api.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSecurityConfigFailClosedTest {

    @Test
    @DisplayName("Production profile with missing issuer fails closed with IllegalStateException")
    void prodProfileMissingIssuerFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtSecurityProperties properties = new JwtSecurityProperties();
        properties.setIssuer(null);
        properties.setAudience("traceback-api");
        properties.setJwkSetUri("https://auth.example.com/.well-known/jwks.json");

        SecurityConfig config = new SecurityConfig(properties, env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, config::jwtDecoder);
        assertTrue(ex.getMessage().contains("traceback.security.jwt.issuer"));
    }

    @Test
    @DisplayName("Production profile with missing audience fails closed with IllegalStateException")
    void prodProfileMissingAudienceFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtSecurityProperties properties = new JwtSecurityProperties();
        properties.setIssuer("https://auth.example.com");
        properties.setAudience("   ");
        properties.setJwkSetUri("https://auth.example.com/.well-known/jwks.json");

        SecurityConfig config = new SecurityConfig(properties, env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, config::jwtDecoder);
        assertTrue(ex.getMessage().contains("traceback.security.jwt.audience"));
    }

    @Test
    @DisplayName("Production profile with missing JWKS and public key location fails closed with IllegalStateException")
    void prodProfileMissingKeySourceFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtSecurityProperties properties = new JwtSecurityProperties();
        properties.setIssuer("https://auth.example.com");
        properties.setAudience("traceback-api");
        properties.setJwkSetUri(null);
        properties.setPublicKeyLocation(null);

        SecurityConfig config = new SecurityConfig(properties, env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, config::jwtDecoder);
        assertTrue(ex.getMessage().contains("jwk-set-uri") || ex.getMessage().contains("public-key-location"));
    }

    @Test
    @DisplayName("Non-production profile falls back to local dev keys safely without throwing")
    void nonProdProfileFallsBackSafely() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        JwtSecurityProperties properties = new JwtSecurityProperties();
        // Unconfigured properties in dev
        properties.setIssuer(null);
        properties.setAudience("traceback-api");

        SecurityConfig config = new SecurityConfig(properties, env);
        assertNotNull(config.jwtDecoder());
    }
}
