package com.traceback.api.security.dev;

import com.traceback.api.config.SecurityConfig;
import com.traceback.api.controller.DevAuthController;
import com.traceback.api.security.JwtSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevAuthProductionExclusionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DevKeyManager.class,
                    DevTokenService.class,
                    DevAuthController.class,
                    JwtSecurityProperties.class
            );

    @Test
    @DisplayName("Production profile with dev-auth enabled fails closed with IllegalStateException")
    void prodProfileWithDevAuthEnabledFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("traceback.security.dev-auth.enabled", "true");

        JwtSecurityProperties properties = new JwtSecurityProperties();
        properties.setIssuer("https://auth.example.com");
        properties.setAudience("traceback-api");
        properties.setJwkSetUri("https://auth.example.com/.well-known/jwks.json");

        SecurityConfig config = new SecurityConfig(properties, env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, config::jwtDecoder);
        assertTrue(ex.getMessage().contains("Development authentication bridge cannot be enabled in 'prod' profile"));
    }

    @Test
    @DisplayName("Production profile excludes dev auth components completely even if enabled property is set")
    void prodProfileExcludesDevAuthBeans() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "traceback.security.dev-auth.enabled=true"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DevKeyManager.class);
                    assertThat(context).doesNotHaveBean(DevTokenService.class);
                    assertThat(context).doesNotHaveBean(DevAuthController.class);
                });
    }

    @Test
    @DisplayName("Dev profile with dev-auth disabled (or missing) excludes dev auth components")
    void devProfileWithDevAuthDisabledExcludesBeans() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "traceback.security.dev-auth.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DevKeyManager.class);
                    assertThat(context).doesNotHaveBean(DevTokenService.class);
                    assertThat(context).doesNotHaveBean(DevAuthController.class);
                });
    }

    @Test
    @DisplayName("Dev profile with dev-auth enabled registers dev auth components")
    void devProfileWithDevAuthEnabledRegistersBeans() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "traceback.security.dev-auth.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DevKeyManager.class);
                    assertThat(context).hasSingleBean(DevTokenService.class);
                    assertThat(context).hasSingleBean(DevAuthController.class);
                });
    }

    @Test
    @DisplayName("Local profile with dev-auth enabled registers dev auth components")
    void localProfileWithDevAuthEnabledRegistersBeans() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "traceback.security.dev-auth.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DevKeyManager.class);
                    assertThat(context).hasSingleBean(DevTokenService.class);
                    assertThat(context).hasSingleBean(DevAuthController.class);
                });
    }
}
