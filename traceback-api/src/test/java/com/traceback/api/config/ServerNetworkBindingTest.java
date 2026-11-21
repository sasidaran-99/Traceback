package com.traceback.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ServerNetworkBindingTest {

    @Configuration
    static class EmptyTestConfig {}

    @Test
    @DisplayName("Development profile ('dev') defaults server.address to 127.0.0.1")
    void devProfileDefaultsToLoopbackBinding() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("dev")
                .run()) {
            String serverAddress = ctx.getEnvironment().getProperty("server.address");
            assertThat(serverAddress).isEqualTo("127.0.0.1");
        }
    }

    @Test
    @DisplayName("Local profile ('local') defaults server.address to 127.0.0.1")
    void localProfileDefaultsToLoopbackBinding() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .run()) {
            String serverAddress = ctx.getEnvironment().getProperty("server.address");
            assertThat(serverAddress).isEqualTo("127.0.0.1");
        }
    }

    @Test
    @DisplayName("Explicit SERVER_ADDRESS environment/property overrides dev loopback default")
    void explicitServerAddressOverridesDevDefault() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("dev")
                .properties("SERVER_ADDRESS=192.168.1.100")
                .run()) {
            String serverAddress = ctx.getEnvironment().getProperty("server.address");
            assertThat(serverAddress).isEqualTo("192.168.1.100");
        }
    }

    @Test
    @DisplayName("Production profile ('prod') does not force server.address to loopback")
    void prodProfileDoesNotForceLoopback() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .run()) {
            String serverAddress = ctx.getEnvironment().getProperty("server.address");
            assertThat(serverAddress).isNotEqualTo("127.0.0.1");
            assertThat(serverAddress).isNull();
        }
    }

    @Test
    @DisplayName("Default profile (no active profiles) does not force server.address to loopback")
    void defaultProfileDoesNotForceLoopback() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyTestConfig.class)
                .web(WebApplicationType.NONE)
                .run()) {
            String serverAddress = ctx.getEnvironment().getProperty("server.address");
            assertThat(serverAddress).isNull();
        }
    }
}
