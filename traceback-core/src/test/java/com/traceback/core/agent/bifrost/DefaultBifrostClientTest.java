package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBifrostClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should successfully send chat completion request with Bearer authorization and parse response")
    void shouldSendChatCompletionSuccessfully() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);

        String jsonResponse = """
                {
                  "id": "chatcmpl-12345",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "gpt-4o",
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
                "http://localhost:8080/v1",
                "sk-bf-secretvirtualkey123456",
                "gpt-4o",
                Duration.ofSeconds(5),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);
        assertThat(client.isAvailable()).isTrue();

        BifrostRequest request = BifrostRequest.of("gpt-4o", List.of(
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
        assertThat(sent.uri().toString()).isEqualTo("http://localhost:8080/v1/chat/completions");
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer sk-bf-secretvirtualkey123456");
        assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
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
                "http://localhost:8080/v1",
                "sk-bf-key",
                "gpt-4o",
                Duration.ofSeconds(5),
                2
        );

        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);
        BifrostResponse response = client.chatCompletion(BifrostRequest.of("gpt-4o", List.of(BifrostMessage.user("test"))));

        assertThat(response.firstContent()).isEqualTo("Analysis succeeded after retry.");
        verify(mockHttp, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("Should fail immediately on HTTP 401 Unauthorized without retries")
    void shouldFailImmediatelyOn401() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> unauthResp = (HttpResponse<String>) mock(HttpResponse.class);

        when(unauthResp.statusCode()).thenReturn(401);
        when(unauthResp.body()).thenReturn("Unauthorized: Invalid API Key");
        doReturn(unauthResp).when(mockHttp).send(any(HttpRequest.class), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8080/v1", "sk-bf-bad-key", "gpt-4o", Duration.ofSeconds(5), 3);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        assertThatThrownBy(() -> client.chatCompletion(BifrostRequest.of("gpt-4o", List.of(BifrostMessage.user("test")))))
                .isInstanceOf(BifrostException.class)
                .hasMessageContaining("401");

        // Exactly 1 attempt because 401 is non-retryable
        verify(mockHttp, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Should mask API key safely to prevent credential leakage")
    void shouldMaskApiKeyCorrectly() {
        BifrostConfig shortKey = new BifrostConfig("http://localhost", "short", "gpt-4o", Duration.ofSeconds(5), 1);
        assertThat(shortKey.maskedApiKey()).isEqualTo("sk-bf-***");

        BifrostConfig longKey = new BifrostConfig("http://localhost", "sk-bf-1234567890abcdef", "gpt-4o", Duration.ofSeconds(5), 1);
        assertThat(longKey.maskedApiKey()).isEqualTo("sk-bf-...cdef");
        assertThat(longKey.maskedApiKey()).doesNotContain("1234567890");

        BifrostConfig emptyKey = new BifrostConfig("http://localhost", null, "gpt-4o", Duration.ofSeconds(5), 1);
        assertThat(emptyKey.maskedApiKey()).isEqualTo("[NOT_SET]");
    }
}
