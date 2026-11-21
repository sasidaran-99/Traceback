package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.EOFException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBifrostClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should successfully send chat completion request with x-bf-vk authorization header and parse response")
    void shouldSendChatCompletionSuccessfully() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);

        String jsonResponse = """
                {
                  "id": "chatcmpl-12345",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "gemini-3.8-flash",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "SUMMARY: HikariCP pool exhausted.\\nROOT CAUSE: Commit abc1234 reduced max pool size."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 30,
                    "total_tokens": 150
                  }
                }
                """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(jsonResponse);
        doReturn(mockResponse).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-secretvirtualkey123456",
                "gemini-3.8-flash",
                Duration.ofSeconds(180),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);
        assertThat(client.isAvailable()).isTrue();

        BifrostRequest request = BifrostRequest.of("gemini-3.8-flash", List.of(
                BifrostMessage.system("You are Traceback AI"),
                BifrostMessage.user("Analyze the failure")
        ));

        BifrostResponse response = client.chatCompletion(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("chatcmpl-12345");
        assertThat(response.firstContent()).contains("SUMMARY: HikariCP pool exhausted.");
        assertThat(response.usage().total_tokens()).isEqualTo(150);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttp).send(captor.capture(), any());

        HttpRequest sent = captor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("http://localhost:8181/v1/chat/completions");
        assertThat(sent.headers().firstValue("x-bf-vk")).contains("sk-bf-secretvirtualkey123456");
        assertThat(sent.headers().firstValue("Authorization")).isEmpty();
        assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
        // For a 180s overall timeout, the default per-attempt timeout is 170s
        assertThat(sent.timeout()).contains(Duration.ofSeconds(170));
    }

    @Test
    @DisplayName("Should retry on HTTP 429 rate limit then succeed")
    void shouldRetryOn429AndSucceed() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> rateLimitResp = (HttpResponse<String>) mock(HttpResponse.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> successResp = (HttpResponse<String>) mock(HttpResponse.class);

        when(rateLimitResp.statusCode()).thenReturn(429);
        when(rateLimitResp.body()).thenReturn("Rate limit exceeded");

        String successJson = """
                {
                  "id": "chatcmpl-retried",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "Analysis succeeded after retry." }
                    }
                  ]
                }
                """;
        when(successResp.statusCode()).thenReturn(200);
        when(successResp.body()).thenReturn(successJson);

        doReturn(rateLimitResp, successResp).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(180),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);
        BifrostResponse response = client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test"))));

        assertThat(response.firstContent()).isEqualTo("Analysis succeeded after retry.");
        verify(mockHttp, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("Should retry on HTTP 503 Service Unavailable and succeed on retry")
    void shouldRetryOn503AndSucceed() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> errResp = (HttpResponse<String>) mock(HttpResponse.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> successResp = (HttpResponse<String>) mock(HttpResponse.class);

        when(errResp.statusCode()).thenReturn(503);
        when(errResp.body()).thenReturn("Service Unavailable: Upstream provider temporary issue");

        String successJson = """
                {
                  "id": "chatcmpl-503-recovered",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "Recovered from 503." }
                    }
                  ]
                }
                """;
        when(successResp.statusCode()).thenReturn(200);
        when(successResp.body()).thenReturn(successJson);

        doReturn(errResp, successResp).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(180),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);
        BifrostResponse response = client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test"))));

        assertThat(response.firstContent()).isEqualTo("Recovered from 503.");
        verify(mockHttp, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("Should fail immediately on HTTP 401 Unauthorized without retries and without leaking response body")
    void shouldFailImmediatelyOn401() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> unauthResp = (HttpResponse<String>) mock(HttpResponse.class);

        when(unauthResp.statusCode()).thenReturn(401);
        when(unauthResp.body()).thenReturn("Unauthorized: Invalid Virtual Key sk-bf-leak-attempt. Prompt: Private project code leak");
        doReturn(unauthResp).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-bad-key", "gemini-3.8-flash", Duration.ofSeconds(5), 3);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .satisfies(ex -> {
                    BifrostException bex = (BifrostException) ex;
                    assertThat(bex.getStatusCode()).isEqualTo(401);
                    assertThat(bex.getMessage())
                            .contains("Upstream returned HTTP 401. Response body omitted for security.")
                            .doesNotContain("Unauthorized: Invalid Virtual Key")
                            .doesNotContain("sk-bf-leak-attempt")
                            .doesNotContain("Private project code leak");
                });

        // Exactly 1 attempt because 401 is non-retryable
        verify(mockHttp, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Should handle network timeout and retry up to maxAttempts")
    void shouldHandleTimeoutAndRetry() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        doThrow(new HttpConnectTimeoutException("Connection timed out after 5000ms"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessageContaining("failed after 3 attempts");

        verify(mockHttp, times(3)).send(any(), any());
    }

    @Test
    @DisplayName("BifrostConfig defaults must use port 8181 (not 8080), gemini-3.8-flash, 180s timeout, and 170s per-attempt timeout")
    void testBifrostConfigDefaults() {
        BifrostConfig config = new BifrostConfig(null, "sk-bf-test", null, null, 0);
        assertThat(config.baseUrl()).isEqualTo("http://localhost:8181/v1");
        assertThat(config.model()).isEqualTo("gemini-3.8-flash");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(config.perAttemptTimeout()).isEqualTo(Duration.ofSeconds(170));
        assertThat(config.maxRetries()).isEqualTo(0);
        assertThat(config.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Should mask API key safely to prevent credential leakage")
    void shouldMaskApiKeyCorrectly() {
        BifrostConfig shortKey = new BifrostConfig("http://localhost:8181", "short", "gemini-3.8-flash", Duration.ofSeconds(5), 1);
        assertThat(shortKey.maskedApiKey()).isEqualTo("sk-bf-***");

        BifrostConfig longKey = new BifrostConfig("http://localhost:8181", "sk-bf-1234567890abcdef", "gemini-3.8-flash", Duration.ofSeconds(5), 1);
        assertThat(longKey.maskedApiKey()).isEqualTo("sk-bf-...cdef");
        assertThat(longKey.maskedApiKey()).doesNotContain("1234567890");

        BifrostConfig emptyKey = new BifrostConfig("http://localhost:8181", null, "gemini-3.8-flash", Duration.ofSeconds(5), 1);
        assertThat(emptyKey.maskedApiKey()).isEqualTo("[NOT_SET]");
    }

    @Test
    @DisplayName("Should enforce overall retry deadline and abort further attempts when total deadline expires")
    void shouldEnforceOverallRetryDeadlineAndNotRetryWhenDeadlineExpired() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            Thread.sleep(120L);
            throw new HttpConnectTimeoutException("Attempt timed out");
        });

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofMillis(100),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessageContaining("failed after 1 attempts");

        verify(mockHttp, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Should cap backoff delay by remaining deadline budget and avoid overshoot")
    void shouldCapBackoffDelayByRemainingBudget() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);
        when(mock503.body()).thenReturn("Service Unavailable");

        // Return 503 on first attempt after 80ms elapsed, with 100ms budget (20ms remaining)
        when(mockHttp.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
            Thread.sleep(80L);
            return mock503;
        });

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofMillis(100),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessageContaining("failed after 1 attempts");
        long elapsed = System.currentTimeMillis() - start;

        // Total elapsed time must be capped tightly around ~100-350ms (not sleeping unconstrained 1000ms backoff)
        assertThat(elapsed).isLessThan(400);
        verify(mockHttp, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Retry attempt timeout must be min(perAttemptTimeout, remainingBudget): Scenario A (remaining > perAttempt) caps at perAttemptTimeout")
    void testRetryRecalculatesAttemptTimeoutFromRemainingMonotonicBudget() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        NanoClock mockClock = mock(NanoClock.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);
        when(mock503.body()).thenReturn("Service Unavailable");

        @SuppressWarnings("unchecked")
        HttpResponse<String> mock200 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock200.statusCode()).thenReturn(200);
        when(mock200.body()).thenReturn("{\"id\":\"c1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");

        doReturn(mock503, mock200).when(mockHttp).send(any(HttpRequest.class), any());

        when(mockClock.nanoTime()).thenReturn(
                0L,                  // startNanos
                0L,                  // attempt 1 loop check (elapsed 0s)
                2_000_000_000L,      // attempt 1 backoff remaining calc (elapsed 2.0s)
                2_250_000_000L,      // attempt 2 loop check (elapsed 2.25s, remaining 177.75s)
                2_250_000_000L       // attempt 2 timeout calc
        );

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(180),
                Duration.ofSeconds(170),
                2
        );
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, mockClock, mockSleeper);

        BifrostResponse resp = client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test"))));
        assertThat(resp).isNotNull();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttp, times(2)).send(captor.capture(), any());

        List<HttpRequest> requests = captor.getAllValues();
        // Attempt 1: min(170s, 180s) = 170s
        assertThat(requests.get(0).timeout()).contains(Duration.ofSeconds(170));
        // Attempt 2: min(170s, 177.75s) = 170s (verifies per-attempt timeout bound, NOT 177.75s)
        assertThat(requests.get(1).timeout()).contains(Duration.ofSeconds(170));
    }

    @Test
    @DisplayName("Retry attempt timeout must be min(perAttemptTimeout, remainingBudget): Scenario B (remaining < perAttempt) caps at remaining budget")
    void testRetryCapsAttemptTimeoutWhenRemainingBudgetIsLessThanPerAttemptTimeout() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        NanoClock mockClock = mock(NanoClock.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);
        when(mock503.body()).thenReturn("Service Unavailable");

        @SuppressWarnings("unchecked")
        HttpResponse<String> mock200 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock200.statusCode()).thenReturn(200);
        when(mock200.body()).thenReturn("{\"id\":\"c2\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");

        doReturn(mock503, mock200).when(mockHttp).send(any(HttpRequest.class), any());

        when(mockClock.nanoTime()).thenReturn(
                0L,                   // startNanos
                0L,                   // attempt 1 loop check (elapsed 0s)
                20_000_000_000L,      // attempt 1 backoff remaining calc (elapsed 20.0s)
                20_250_000_000L,      // attempt 2 loop check (elapsed 20.25s, remaining 39.75s)
                20_250_000_000L       // attempt 2 timeout calc
        );

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(60),
                Duration.ofSeconds(45),
                2
        );
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, mockClock, mockSleeper);

        BifrostResponse resp = client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test"))));
        assertThat(resp).isNotNull();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttp, times(2)).send(captor.capture(), any());

        List<HttpRequest> requests = captor.getAllValues();
        // Attempt 1: min(45s, 60s) = 45s
        assertThat(requests.get(0).timeout()).contains(Duration.ofSeconds(45));
        // Attempt 2: min(45s, 39.75s) = 39.75s (verifies actual remaining-budget capping)
        assertThat(requests.get(1).timeout()).contains(Duration.ofNanos(39_750_000_000L));
    }

    @Test
    @DisplayName("Should preserve HTTP status code and response diagnostic body when retry attempt is skipped by post-backoff threshold")
    void testHttpDiagnosticsPreservedWhenRetrySkippedByThreshold() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        NanoClock mockClock = mock(NanoClock.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);
        when(mock503.body()).thenReturn("{\"error\":\"This model is currently experiencing high demand\"}");
        doReturn(mock503).when(mockHttp).send(any(HttpRequest.class), any());

        when(mockClock.nanoTime()).thenReturn(
                0L,                   // startNanos
                0L,                   // attempt 1 loop check (elapsed 0s)
                45_000_000_000L,      // attempt 1 backoff calc (elapsed 45.0s)
                45_250_000_000L       // attempt 2 loop check (elapsed 45.25s, remaining 14.75s < 15s)
        );

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(60),
                Duration.ofSeconds(45),
                2
        );
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, mockClock, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .satisfies(ex -> {
                    BifrostException bex = (BifrostException) ex;
                    // 1. Preserves HTTP 503 status code (not 0)
                    assertThat(bex.getStatusCode()).isEqualTo(503);
                    // 2. Preserves chained cause (not null)
                    assertThat(bex.getCause()).isNotNull();
                    assertThat(bex.getCause()).isInstanceOf(BifrostException.class);
                    assertThat(bex.getCause().getMessage()).contains("Upstream returned HTTP 503. Response body omitted for security.");
                    // 3. Message contains exact dispatched attempts (1, not 2), status code, and generic security diagnostic
                    assertThat(bex.getMessage())
                            .contains("Bifrost call failed after 1 attempts")
                            .contains("503")
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("This model is currently experiencing high demand");
                });

        // Exactly 1 attempt dispatched to network; attempt 2 skipped post-backoff
        verify(mockHttp, times(1)).send(any(), any());
        // Exactly 1 backoff sleep executed (250ms)
        verify(mockSleeper, times(1)).sleep(250L);
    }

    @Test
    @DisplayName("60-second budget boundary: 45s attempt + 250ms backoff leaves 14.75s (<15s threshold) and reports exactly 1 dispatched attempt")
    void testSixtySecondBoundarySkipsAttemptTwoAndReportsExactlyOneDispatchedAttempt() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        NanoClock mockClock = mock(NanoClock.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        doThrow(new HttpConnectTimeoutException("Attempt 1 connection timeout after 45s"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        when(mockClock.nanoTime()).thenReturn(
                0L,
                0L,
                45_000_000_000L,
                45_250_000_000L
        );

        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(60),
                Duration.ofSeconds(45),
                2
        );
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, mockClock, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 1 attempts");

        // Exactly 1 physical transmission dispatched
        verify(mockHttp, times(1)).send(any(), any());
        verify(mockSleeper, times(1)).sleep(250L);
    }

    @Test
    @DisplayName("Non-allowlisted IOException (e.g. SSLException) fails fast on attempt 1 without sleeping or retrying")
    void testNonAllowlistedIOExceptionFailsFastAndNeverInvokesSleeper() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        doThrow(new javax.net.ssl.SSLException("SSL handshake certificate error"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 1 attempts")
                .hasCauseInstanceOf(javax.net.ssl.SSLException.class);

        verify(mockHttp, times(1)).send(any(), any());
        verify(mockSleeper, never()).sleep(anyLong());
    }

    @Test
    @DisplayName("Bare SocketException (e.g. Connection reset) fails fast on attempt 1 without sleeping or retrying")
    void testBareSocketExceptionFailsFastAndNeverInvokesSleeper() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        doThrow(new SocketException("Connection reset"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 1 attempts")
                .hasCauseInstanceOf(SocketException.class);

        verify(mockHttp, times(1)).send(any(), any());
        verify(mockSleeper, never()).sleep(anyLong());
    }

    @Test
    @DisplayName("EOFException (e.g. unexpected stream termination) fails fast on attempt 1 without sleeping or retrying")
    void testEofExceptionFailsFastAndNeverInvokesSleeper() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        doThrow(new EOFException("Unexpected end of ZLIB input stream"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 1 attempts")
                .hasCauseInstanceOf(EOFException.class);

        verify(mockHttp, times(1)).send(any(), any());
        verify(mockSleeper, never()).sleep(anyLong());
    }

    @Test
    @DisplayName("ProtocolException fails fast on attempt 1 without sleeping or retrying")
    void testProtocolExceptionFailsFastAndNeverInvokesSleeper() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        doThrow(new ProtocolException("Server redirected too many times (20)"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 1 attempts")
                .hasCauseInstanceOf(ProtocolException.class);

        verify(mockHttp, times(1)).send(any(), any());
        verify(mockSleeper, never()).sleep(anyLong());
    }

    @Test
    @DisplayName("Interruption during backoff restores interrupt status and halts retries immediately")
    void testInterruptedBackoffInHttpStatusAndNetworkBranches() throws Exception {
        // Branch A: HTTP 503 response interrupted during backoff
        {
            HttpClient mockHttp = mock(HttpClient.class);
            Sleeper mockSleeper = mock(Sleeper.class);

            @SuppressWarnings("unchecked")
            HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
            when(mock503.statusCode()).thenReturn(503);
            when(mock503.body()).thenReturn("Service Unavailable");
            doReturn(mock503).when(mockHttp).send(any(HttpRequest.class), any());

            doThrow(new InterruptedException("Interrupted in sleep"))
                    .when(mockSleeper).sleep(anyLong());

            BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
            DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

            assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                    .isInstanceOf(BifrostException.class)
                    .hasMessage("Bifrost request interrupted during retry backoff");

            assertThat(Thread.interrupted()).isTrue();
            verify(mockHttp, times(1)).send(any(), any());
        }

        // Branch B: Network retryable exception interrupted during backoff
        {
            HttpClient mockHttp = mock(HttpClient.class);
            Sleeper mockSleeper = mock(Sleeper.class);

            doThrow(new HttpConnectTimeoutException("Connect timeout"))
                    .when(mockHttp).send(any(HttpRequest.class), any());

            doThrow(new InterruptedException("Interrupted in sleep"))
                    .when(mockSleeper).sleep(anyLong());

            BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
            DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

            assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                    .isInstanceOf(BifrostException.class)
                    .hasMessage("Bifrost request interrupted during retry backoff");

            assertThat(Thread.interrupted()).isTrue();
            verify(mockHttp, times(1)).send(any(), any());
        }
    }

    @Test
    @DisplayName("Negative maxRetries throws IllegalArgumentException")
    void testBifrostConfigNegativeMaxRetriesThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BifrostConfig("http://localhost:8181", "key", "model", Duration.ofSeconds(180), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries cannot be negative");

        assertThatThrownBy(() -> new BifrostConfig("http://localhost:8181", "key", "model", Duration.ofSeconds(180), -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries cannot be negative");
    }

    @Test
    @DisplayName("maxRetries = 0 remains valid and performs exactly one attempt without retries")
    void testBifrostConfigZeroMaxRetriesRemainsValid() throws Exception {
        BifrostConfig config = new BifrostConfig("http://localhost:8181", "key", "model", Duration.ofSeconds(180), 0);
        assertThat(config.maxRetries()).isEqualTo(0);

        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);
        doThrow(new HttpConnectTimeoutException("Immediate connect failure"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 1 attempts");

        verify(mockHttp, times(1)).send(any(), any());
        verify(mockSleeper, never()).sleep(anyLong());
    }

    @Test
    @DisplayName("Exponential backoff progresses 250, 500, 1000, 2000 ms and remains capped at 2000 ms")
    void testExponentialBackoffProgressionAndCap() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);

        doThrow(new HttpConnectTimeoutException("Attempt timeout"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        // Configure 5 retries (6 attempts total) with generous deadline
        BifrostConfig config = new BifrostConfig(
                "http://localhost:8181/v1",
                "sk-bf-key",
                "gemini-3.8-flash",
                Duration.ofSeconds(180),
                5
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessage("Bifrost call failed after 6 attempts");

        ArgumentCaptor<Long> sleepCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mockSleeper, times(5)).sleep(sleepCaptor.capture());

        List<Long> capturedDelays = sleepCaptor.getAllValues();
        assertThat(capturedDelays).containsExactly(250L, 500L, 1000L, 2000L, 2000L);
    }

    @Test
    @DisplayName("Oversized response body containing sensitive project details is omitted from exception messages")
    void testOversizedResponseBodyDiagnosticIsBounded() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);

        // Generate 4800-character payload containing sensitive internal stacktrace & code snippets
        String hugeBody = "INTERNAL_PROJECT_SECRET_SOURCE_CODE_LEAK_CHUNK_".repeat(100);
        when(mock503.body()).thenReturn(hugeBody);
        doReturn(mock503).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 0);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .satisfies(ex -> {
                    BifrostException bex = (BifrostException) ex;
                    assertThat(bex.getStatusCode()).isEqualTo(503);
                    assertThat(bex.getMessage())
                            .contains("Bifrost call failed after 1 attempts")
                            .contains("503")
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("INTERNAL_PROJECT_SECRET_SOURCE_CODE_LEAK_CHUNK");
                    assertThat(bex.getCause()).isNotNull();
                    assertThat(bex.getCause().getMessage())
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("INTERNAL_PROJECT_SECRET_SOURCE_CODE_LEAK_CHUNK");
                });
    }

    @Test
    @DisplayName("Sensitive diagnostic values such as API keys, Bearer tokens, and user prompts never appear in exception messages")
    void testSensitiveDiagnosticValuesAreRedacted() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);

        String sensitiveBody = "{\"error\": \"Authentication failed with virtual key sk-bf-secretvirtualkey987654. Authorization: Bearer supersecrettoken. User prompt: SELECT * FROM confidential_users\"}";
        when(mock503.body()).thenReturn(sensitiveBody);
        doReturn(mock503).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 0);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .satisfies(ex -> {
                    BifrostException bex = (BifrostException) ex;
                    assertThat(bex.getStatusCode()).isEqualTo(503);
                    assertThat(bex.getMessage())
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("sk-bf-secretvirtualkey987654")
                            .doesNotContain("supersecrettoken")
                            .doesNotContain("confidential_users");
                    assertThat(bex.getCause()).isNotNull();
                    assertThat(bex.getCause().getMessage())
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("sk-bf-secretvirtualkey987654")
                            .doesNotContain("supersecrettoken")
                            .doesNotContain("confidential_users");
                });
    }

    @Test
    @DisplayName("Response body containing sensitive project text, passwords, and model prompt context never appears in final exception message after retries")
    void testResponseBodyWithSensitiveProjectTextNeverAppearsInExceptionMessages() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        Sleeper mockSleeper = mock(Sleeper.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mock503 = (HttpResponse<String>) mock(HttpResponse.class);
        when(mock503.statusCode()).thenReturn(503);

        String sensitiveResponseBody = """
                {
                  "error": {
                    "code": 503,
                    "message": "Service overloaded processing prompt: class SecretPaymentGateway { private String secretKey = \\"sk-live-9988776655\\"; }",
                    "status": "UNAVAILABLE"
                  }
                }
                """;
        when(mock503.body()).thenReturn(sensitiveResponseBody);
        doReturn(mock503).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 1);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper, NanoClock.SYSTEM, mockSleeper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .satisfies(ex -> {
                    BifrostException bex = (BifrostException) ex;
                    assertThat(bex.getStatusCode()).isEqualTo(503);
                    assertThat(bex.getMessage())
                            .contains("Bifrost call failed after 2 attempts")
                            .contains("503")
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("SecretPaymentGateway")
                            .doesNotContain("secretKey")
                            .doesNotContain("sk-live-9988776655")
                            .doesNotContain("UNAVAILABLE");

                    assertThat(bex.getCause()).isNotNull();
                    assertThat(bex.getCause().getMessage())
                            .contains("Upstream returned HTTP 503. Response body omitted for security.")
                            .doesNotContain("SecretPaymentGateway")
                            .doesNotContain("sk-live-9988776655");
                });

        verify(mockHttp, times(2)).send(any(), any());
        verify(mockSleeper, times(1)).sleep(250L);
    }

    @Test
    @DisplayName("BifrostConfig validation rules for timeouts and retries")
    void testBifrostConfigValidation() {
        // Negative overall timeout throws IllegalArgumentException
        assertThatThrownBy(() -> new BifrostConfig("http://localhost:8181", "key", "model", Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overall timeout must be strictly positive");

        // Zero overall timeout throws IllegalArgumentException
        assertThatThrownBy(() -> new BifrostConfig("http://localhost:8181", "key", "model", Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overall timeout must be strictly positive");

        // Per-attempt timeout greater than overall timeout is clamped to overall timeout
        BifrostConfig clamped = new BifrostConfig("http://localhost:8181", "key", "model", Duration.ofSeconds(30), Duration.ofSeconds(60), 1);
        assertThat(clamped.perAttemptTimeout()).isEqualTo(Duration.ofSeconds(30));

        // Negative retries throws IllegalArgumentException
        assertThatThrownBy(() -> new BifrostConfig("http://localhost:8181", "key", "model", Duration.ofSeconds(30), -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries cannot be negative");
    }
}
