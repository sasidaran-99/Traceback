package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default implementation of {@link BifrostClient} using Java 21's {@link HttpClient}.
 */
public class DefaultBifrostClient implements BifrostClient {
    private static final Logger log = LoggerFactory.getLogger(DefaultBifrostClient.class);

    private final BifrostConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpointUri;

    public DefaultBifrostClient(BifrostConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build(), new ObjectMapper());
    }

    public DefaultBifrostClient(BifrostConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = config != null ? config : BifrostConfig.fromEnvironment();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpointUri = resolveEndpoint(this.config.baseUrl());
    }

    private static URI resolveEndpoint(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed + "/chat/completions");
    }

    @Override
    public boolean isAvailable() {
        return config.isConfigured();
    }

    @Override
    public BifrostConfig getConfig() {
        return config;
    }

    @Override
    public BifrostResponse chatCompletion(BifrostRequest request) {
        if (!isAvailable()) {
            throw new BifrostException("Bifrost client is not configured (API key missing)");
        }

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new BifrostException("Failed to serialize Bifrost request", e);
        }

        int maxAttempts = 1 + config.maxRetries();
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(endpointUri)
                        .timeout(config.timeout())
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

                log.debug("Sending chat completion to Bifrost endpoint {} (attempt {}/{}, model={}, key={})",
                        endpointUri, attempt, maxAttempts, request.model(), config.maskedApiKey());

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return objectMapper.readValue(response.body(), BifrostResponse.class);
                }

                if (shouldRetry(status) && attempt < maxAttempts) {
                    log.warn("Bifrost returned HTTP {}. Retrying attempt {}/{}...", status, attempt, maxAttempts);
                    backoff(attempt);
                    continue;
                }

                throw new BifrostException("Bifrost API error response: " + response.body(), status);

            } catch (IOException | InterruptedException e) {
                lastException = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new BifrostException("Bifrost request interrupted", e);
                }
                if (attempt < maxAttempts) {
                    log.warn("Bifrost network exception on attempt {}/{}: {}. Retrying...", attempt, maxAttempts, e.getMessage());
                    backoff(attempt);
                }
            }
        }

        throw new BifrostException("Bifrost call failed after " + maxAttempts + " attempts", lastException);
    }

    private boolean shouldRetry(int status) {
        // Retry on 429 Too Many Requests, or 5xx Server / Gateway errors
        return status == 429 || (status >= 500 && status < 600);
    }

    private void backoff(int attempt) {
        try {
            long delay = Math.min(100L * attempt, 1000L);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
