package com.traceback.core.agent.bifrost;

import java.time.Duration;

/**
 * Configuration for the Bifrost AI Gateway client.
 * All credentials and routing models are driven by environment variables.
 */
public record BifrostConfig(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        int maxRetries
) {
    public static final String DEFAULT_BASE_URL = "http://localhost:8080/v1";
    public static final String DEFAULT_MODEL = "gpt-4o";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_RETRIES = 2;

    public BifrostConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (maxRetries < 0) {
            maxRetries = 0;
        }
    }

    public static BifrostConfig fromEnvironment() {
        String envUrl = System.getenv("BIFROST_BASE_URL");
        String envKey = System.getenv("BIFROST_API_KEY");
        String envModel = System.getenv("BIFROST_MODEL");
        String envTimeout = System.getenv("BIFROST_TIMEOUT_SECONDS");
        String envRetries = System.getenv("BIFROST_MAX_RETRIES");

        Duration timeout = DEFAULT_TIMEOUT;
        if (envTimeout != null && !envTimeout.isBlank()) {
            try {
                timeout = Duration.ofSeconds(Long.parseLong(envTimeout.trim()));
            } catch (NumberFormatException ignored) {}
        }

        int retries = DEFAULT_MAX_RETRIES;
        if (envRetries != null && !envRetries.isBlank()) {
            try {
                retries = Integer.parseInt(envRetries.trim());
            } catch (NumberFormatException ignored) {}
        }

        return new BifrostConfig(envUrl, envKey, envModel, timeout, retries);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "[NOT_SET]";
        }
        if (apiKey.length() <= 8) {
            return "sk-bf-***";
        }
        return apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
