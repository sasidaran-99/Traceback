package com.traceback.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-Origin Resource Sharing (CORS) configuration for the Traceback frontend dashboard.
 * Supports configurable allowed origin patterns for production deployment via
 * environment variable CORS_ALLOWED_ORIGIN_PATTERNS, defaulting to localhost for development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${traceback.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*]}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
