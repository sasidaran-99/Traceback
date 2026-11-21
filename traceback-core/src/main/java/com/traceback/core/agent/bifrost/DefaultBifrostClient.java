package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of {@link BifrostClient} using Java 21's {@link HttpClient}.
 * Supports bounded retry deadlines, per-attempt timeout capping, exponential backoff,
 * response omission security hardening, and diagnostic error reporting.
 */
public class DefaultBifrostClient implements BifrostClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultBifrostClient.class);

    private final BifrostConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NanoClock nanoClock;
    private final Sleeper sleeper;
    private final URI endpointUri;

    public DefaultBifrostClient(BifrostConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config != null && config.perAttemptTimeout() != null ? config.perAttemptTimeout() : Duration.ofSeconds(10)).build(), new ObjectMapper());
    }

    public DefaultBifrostClient(BifrostConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this(config, httpClient, objectMapper, NanoClock.SYSTEM, Sleeper.SYSTEM);
    }

    public DefaultBifrostClient(BifrostConfig config, HttpClient httpClient, ObjectMapper objectMapper,
                                NanoClock nanoClock, Sleeper sleeper) {
        this.config = config != null ? config : BifrostConfig.fromEnvironment();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.nanoClock = nanoClock != null ? nanoClock : NanoClock.SYSTEM;
        this.sleeper = sleeper != null ? sleeper : Sleeper.SYSTEM;
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

        long startNanos = nanoClock.nanoTime();
        long budgetNanos = config.timeout().toNanos();
        int maxAttempts = 1 + config.maxRetries();

        int attempt = 0;
        int dispatchedAttempts = 0;
        int lastHttpStatus = 0;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;

            long elapsedNanos = nanoClock.nanoTime() - startNanos;
            long remainingNanos = budgetNanos - elapsedNanos;

            // Post-backoff threshold check: if remaining budget cannot support a viable 15s attempt, skip retry
            if (attempt > 1 && remainingNanos < BifrostConfig.MIN_VIABLE_ATTEMPT.toNanos()) {
                log.warn("Remaining budget ({}ms) after backoff is below minimum viable threshold (15s); skipping retry attempt {}",
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos), attempt);
                break;
            }

            if (remainingNanos <= TimeUnit.MILLISECONDS.toNanos(50)) {
                log.warn("Bifrost overall retry deadline of {}s exceeded; aborting further retry attempts", config.timeout().toSeconds());
                break;
            }

            // Timeout recalculated dynamically from monotonic clock immediately before dispatch:
            // attemptTimeout = min(perAttemptTimeout, remainingBudget)
            Duration remaining = Duration.ofNanos(remainingNanos);
            Duration targetAttempt = config.perAttemptTimeout();
            Duration attemptTimeout = remaining.compareTo(targetAttempt) < 0 ? remaining : targetAttempt;

            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(endpointUri)
                        .timeout(attemptTimeout)
                        .header("Content-Type", "application/json")
                        .header("x-bf-vk", config.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

                log.debug("Sending chat completion to Bifrost endpoint {} (iteration {}/{}, dispatched={}, model={}, key={})",
                        endpointUri, attempt, maxAttempts, dispatchedAttempts + 1, request.model(), config.maskedApiKey());

                dispatchedAttempts++;
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return objectMapper.readValue(response.body(), BifrostResponse.class);
                }

                if (shouldRetry(status)) {
                    lastHttpStatus = status;
                    lastException = new BifrostException(responseBodyOmittedDiagnostic(status), lastHttpStatus);

                    if (attempt < maxAttempts) {
                        long currentRemainingNanos = budgetNanos - (nanoClock.nanoTime() - startNanos);
                        log.warn("Bifrost returned HTTP {}. Executing backoff for attempt {}/{}...", status, attempt, maxAttempts);
                        executeBackoff(attempt, currentRemainingNanos);
                        continue;
                    }
                    break;
                }

                throw new BifrostException(responseBodyOmittedDiagnostic(status), status);

            } catch (IOException | InterruptedException e) {
                lastException = e;
                lastHttpStatus = 0;

                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new BifrostException("Bifrost request interrupted", e);
                }

                if (isRetryableException((IOException) e) && attempt < maxAttempts) {
                    long currentRemainingNanos = budgetNanos - (nanoClock.nanoTime() - startNanos);
                    log.warn("Bifrost retryable network exception on attempt {}/{}: {}. Executing backoff...",
                            attempt, maxAttempts, e.getMessage());
                    executeBackoff(attempt, currentRemainingNanos);
                    continue;
                }

                // Non-allowlisted IOException fails fast: zero retries, no sleeper invoked
                break;
            }
        }

        if (lastHttpStatus > 0) {
            throw new BifrostException(
                    "Bifrost call failed after " + dispatchedAttempts + " attempts (last status: " + lastHttpStatus + "): "
                            + responseBodyOmittedDiagnostic(lastHttpStatus),
                    lastException,
                    lastHttpStatus
            );
        }

        throw new BifrostException("Bifrost call failed after " + dispatchedAttempts + " attempts", lastException);
    }

    private boolean shouldRetry(int status) {
        // Retry on 429 Too Many Requests, or 5xx Server / Gateway errors
        return status == 429 || (status >= 500 && status < 600);
    }

    /**
     * Intentional Safety Deviation:
     * Ambiguous mid-request stream failures (bare SocketException, EOFException) and non-retryable
     * transport errors (SSLException, ProtocolException, generic IOException) fail fast on attempt 1.
     * Only pre-transmission or explicit gateway/connect timeouts are retried:
     * 1. HttpConnectTimeoutException (subclass of HttpTimeoutException) -> safe (TCP connection never established)
     * 2. ConnectException -> safe (TCP connection refused/unreachable)
     * 3. HttpTimeoutException -> retryable gateway timeout
     */
    private boolean isRetryableException(IOException e) {
        return e instanceof HttpConnectTimeoutException
                || e instanceof ConnectException
                || e instanceof HttpTimeoutException;
    }

    static String responseBodyOmittedDiagnostic(int status) {
        return "Upstream returned HTTP " + status + ". Response body omitted for security.";
    }

    private void executeBackoff(int attempt, long remainingNanos) {
        try {
            long baseDelayMs = 250L;
            long maxDelayMs = 2000L;
            long desiredDelayMs = Math.min(baseDelayMs << (attempt - 1), maxDelayMs);
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            long delayMs = Math.min(desiredDelayMs, Math.max(0, remainingMs));
            if (delayMs > 0) {
                sleeper.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BifrostException("Bifrost request interrupted during retry backoff", e);
        }
    }
}
