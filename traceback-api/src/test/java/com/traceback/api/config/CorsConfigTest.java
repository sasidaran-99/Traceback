package com.traceback.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    @Test
    @DisplayName("Development environment defaults to localhost patterns when unconfigured")
    void devDefaultsToLocalhostPatterns() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        CorsConfig config = new CorsConfig("", env);

        assertThat(config.isProduction()).isFalse();
        assertThat(config.getAllowedOriginPatterns())
                .containsExactlyInAnyOrder("http://localhost:[*]", "http://127.0.0.1:[*]");
    }

    @Test
    @DisplayName("Development environment respects explicitly configured origin patterns")
    void devRespectsCustomOrigins() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        CorsConfig config = new CorsConfig("http://localhost:3000,http://localhost:5173", env);

        assertThat(config.getAllowedOriginPatterns())
                .containsExactly("http://localhost:3000", "http://localhost:5173");
    }

    @Test
    @DisplayName("Production environment accepts valid explicit non-localhost domain origins")
    void prodAcceptsExplicitTrustedOrigins() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        CorsConfig config = new CorsConfig("https://traceback.internal.company.com,https://sre.company.com", env);

        assertThat(config.isProduction()).isTrue();
        assertThat(config.getAllowedOriginPatterns())
                .containsExactly("https://traceback.internal.company.com", "https://sre.company.com");
    }

    @Test
    @DisplayName("Production environment fails safely with exception when origins are unconfigured")
    void prodFailsSafelyWhenUnconfigured() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new CorsConfig("", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production CORS security failure")
                .hasMessageContaining("must be explicitly configured");
    }

    @Test
    @DisplayName("Production environment rejects localhost origin patterns")
    void prodRejectsLocalhost() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new CorsConfig("http://localhost:8080", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Localhost and open wildcards are strictly prohibited in 'prod' profile");
    }

    @Test
    @DisplayName("Production environment rejects open wildcard origin (*)")
    void prodRejectsOpenWildcard() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new CorsConfig("*", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Localhost and open wildcards are strictly prohibited in 'prod' profile");
    }
}
