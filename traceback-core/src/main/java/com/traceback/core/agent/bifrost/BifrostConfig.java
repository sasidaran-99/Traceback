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
        Duration perAttemptTimeout,
        int maxRetries
) {
    public static final String DEFAULT_BASE_URL = "http://localhost:8181/v1";
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    public static final Duration DEFAULT_PER_ATTEMPT_TIMEOUT = Duration.ofSeconds(170);
    public static final Duration MIN_VIABLE_ATTEMPT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_RETRIES = 2;

    public BifrostConfig(String baseUrl, String apiKey, String model, Duration timeout, int maxRetries) {
        this(baseUrl, apiKey, model, timeout, null, maxRetries);
    }

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
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Overall timeout must be strictly positive");
        }
        if (perAttemptTimeout == null) {
            perAttemptTimeout = timeout.compareTo(DEFAULT_PER_ATTEMPT_TIMEOUT) >= 0
                    ? DEFAULT_PER_ATTEMPT_TIMEOUT
                    : timeout;
        }
        if (perAttemptTimeout.isNegative() || perAttemptTimeout.isZero()) {
            throw new IllegalArgumentException("Per-attempt timeout must be strictly positive");
        }
        if (perAttemptTimeout.compareTo(timeout) > 0) {
            perAttemptTimeout = timeout;
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
    }

    public static BifrostConfig fromEnvironment() {
        String envUrl = System.getenv("BIFROST_BASE_URL");
        String envKey = System.getenv("BIFROST_API_KEY");
        String envModel = System.getenv("BIFROST_MODEL");
        String envTimeout = System.getenv("BIFROST_TIMEOUT_SECONDS");
        String envPerAttemptTimeout = System.getenv("BIFROST_PER_ATTEMPT_TIMEOUT_SECONDS");
        String envRetries = System.getenv("BIFROST_MAX_RETRIES");

        Duration timeout = DEFAULT_TIMEOUT;
        if (envTimeout != null && !envTimeout.isBlank()) {
            try {
                timeout = Duration.ofSeconds(Long.parseLong(envTimeout.trim()));
            } catch (NumberFormatException ignored) {}
        }

        Duration perAttemptTimeout = null;
        if (envPerAttemptTimeout != null && !envPerAttemptTimeout.isBlank()) {
            try {
                perAttemptTimeout = Duration.ofSeconds(Long.parseLong(envPerAttemptTimeout.trim()));
            } catch (NumberFormatException ignored) {}
        }

        int retries = DEFAULT_MAX_RETRIES;
        if (envRetries != null && !envRetries.isBlank()) {
            try {
                retries = Integer.parseInt(envRetries.trim());
            } catch (NumberFormatException ignored) {}
        }

        return new BifrostConfig(envUrl, envKey, envModel, timeout, perAttemptTimeout, retries);
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
