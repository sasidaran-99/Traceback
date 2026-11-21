package com.traceback.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Cross-Origin Resource Sharing (CORS) configuration for the Traceback frontend dashboard.
 * Enforces strict production hardening while maintaining frictionless local Vite development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;
    private final boolean isProduction;

    public CorsConfig(
            @Value("${traceback.cors.allowed-origin-patterns:}") String allowedOriginsConfig,
            Environment env
    ) {
        this.isProduction = Arrays.asList(env.getActiveProfiles()).contains("prod");

        if (this.isProduction) {
            if (allowedOriginsConfig == null || allowedOriginsConfig.trim().isEmpty()) {
                throw new IllegalStateException(
                        "Production CORS security failure: 'traceback.cors.allowed-origin-patterns' " +
                        "(or environment variable CORS_ALLOWED_ORIGIN_PATTERNS) must be explicitly configured " +
                        "with trusted domain(s) in 'prod' profile. Wildcards and unconfigured origins are forbidden."
                );
            }

            String[] origins = parseOrigins(allowedOriginsConfig);
            for (String origin : origins) {
                if (origin.equals("*") || origin.contains("localhost") || origin.contains("127.0.0.1")) {
                    throw new IllegalStateException(
                            "Production CORS security failure: Origin '" + origin + "' is not permitted in production. " +
                            "Localhost and open wildcards are strictly prohibited in 'prod' profile."
                    );
                }
            }
            this.allowedOriginPatterns = origins;
        } else {
            // Development and test defaults: restrict to exact Vite dev server origins
            if (allowedOriginsConfig != null && !allowedOriginsConfig.trim().isEmpty()) {
                this.allowedOriginPatterns = parseOrigins(allowedOriginsConfig);
            } else {
                this.allowedOriginPatterns = new String[] { "http://localhost:5173", "http://127.0.0.1:5173" };
            }
        }
    }

    private static String[] parseOrigins(String config) {
        return Arrays.stream(config.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOriginPatterns != null && allowedOriginPatterns.length > 0) {
            java.util.List<String> effectivePatterns = new java.util.ArrayList<>();
            for (String p : allowedOriginPatterns) {
                effectivePatterns.add(p);
                if (p.contains("[*]")) {
                    effectivePatterns.add(p.replace("[*]", "*"));
                }
            }
            registry.addMapping("/api/**")
                    .allowedOriginPatterns(effectivePatterns.toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        }
    }

    public String[] getAllowedOriginPatterns() {
        return allowedOriginPatterns != null ? allowedOriginPatterns.clone() : new String[0];
    }

    public boolean isProduction() {
        return isProduction;
    }
}
