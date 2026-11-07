package com.traceback.core.agent.tavily;

import java.time.Duration;

/**
 * Configuration for the Tavily technical research service.
 * Driven by environment variables with safe defaults.
 */
public record TavilyConfig(
        String baseUrl,
        String apiKey,
        int maxSearchesPerInvestigation,
        Duration timeout
) {
    public static final String DEFAULT_BASE_URL = "https://api.tavily.com";
    public static final int DEFAULT_MAX_SEARCHES = 3;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public TavilyConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (maxSearchesPerInvestigation <= 0) {
            maxSearchesPerInvestigation = DEFAULT_MAX_SEARCHES;
        }
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
    }

    public static TavilyConfig fromEnvironment() {
        String envUrl = System.getenv("TAVILY_BASE_URL");
        String envKey = System.getenv("TAVILY_API_KEY");
        String envMaxSearches = System.getenv("TAVILY_MAX_SEARCHES");
        String envTimeout = System.getenv("TAVILY_TIMEOUT_SECONDS");

        int maxSearches = DEFAULT_MAX_SEARCHES;
        if (envMaxSearches != null && !envMaxSearches.isBlank()) {
            try {
                maxSearches = Integer.parseInt(envMaxSearches.trim());
            } catch (NumberFormatException ignored) {}
        }

        Duration timeout = DEFAULT_TIMEOUT;
        if (envTimeout != null && !envTimeout.isBlank()) {
            try {
                timeout = Duration.ofSeconds(Long.parseLong(envTimeout.trim()));
            } catch (NumberFormatException ignored) {}
        }

        return new TavilyConfig(envUrl, envKey, maxSearches, timeout);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "[NOT_SET]";
        }
        if (apiKey.length() <= 8) {
            return "tvly-***";
        }
        return apiKey.substring(0, 5) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
