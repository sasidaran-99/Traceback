package com.traceback.core.agent.tavily;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTavilyResearchServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should successfully execute technical research query and parse findings")
    void shouldExecuteResearchSuccessfully() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);

        String tavilyJson = """
                {
                  "query": "HikariPool Connection is not available",
                  "answer": "HikariCP pool exhaustion occurs when connections exceed maximumPoolSize.",
                  "results": [
                    {
                      "title": "HikariCP Connection Pool Exhaustion Guide",
                      "url": "https://github.com/brettwooldridge/HikariCP/wiki/Pool-Analysis",
                      "content": "When all connections are in use and maximumPoolSize is reached, requests timeout.",
                      "score": 0.95
                    },
                    {
                      "title": "Spring Boot HikariCP Configuration",
                      "url": "https://docs.spring.io/spring-boot/docs/current/reference/html/data.html",
                      "content": "spring.datasource.hikari.maximum-pool-size controls connection ceiling.",
                      "score": 0.88
                    }
                  ],
                  "response_time": 0.42
                }
                """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(tavilyJson);
        doReturn(mockResponse).when(mockHttp).send(any(HttpRequest.class), any());

        TavilyConfig config = new TavilyConfig(
                "https://api.tavily.com",
                "tvly-secretkey12345",
                3,
                Duration.ofSeconds(5)
        );

        DefaultTavilyResearchService service = new DefaultTavilyResearchService(config, mockHttp, objectMapper);
        assertThat(service.isAvailable()).isTrue();

        List<ExternalResearchFinding> findings = service.research("HikariPool Connection is not available", "HikariCP");

        assertThat(findings).hasSize(2);
        ExternalResearchFinding first = findings.get(0);
        assertThat(first.findingId()).startsWith("RES-TAVILY-");
        assertThat(first.title()).isEqualTo("HikariCP Connection Pool Exhaustion Guide");
        assertThat(first.url()).isEqualTo("https://github.com/brettwooldridge/HikariCP/wiki/Pool-Analysis");
        assertThat(first.relevanceScore()).isEqualTo(0.95);

        // Verify request payload and headers
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttp).send(captor.capture(), any());

        HttpRequest sent = captor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("https://api.tavily.com/search");
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer tvly-secretkey12345");
    }

    @Test
    @DisplayName("Should cache identical search queries within service instance to avoid redundant API consumption")
    void shouldCacheDuplicateQueries() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);

        String json = """
                {
                  "query": "cached query",
                  "results": [
                    { "title": "Result", "url": "https://example.com", "content": "Content", "score": 0.9 }
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        doReturn(mockResponse).when(mockHttp).send(any(HttpRequest.class), any());

        TavilyConfig config = new TavilyConfig("https://api.tavily.com", "tvly-key", 3, Duration.ofSeconds(5));
        DefaultTavilyResearchService service = new DefaultTavilyResearchService(config, mockHttp, objectMapper);

        List<ExternalResearchFinding> firstCall = service.research("test query", null);
        List<ExternalResearchFinding> secondCall = service.research("test query", null);

        assertThat(firstCall).hasSize(1);
        assertThat(secondCall).hasSize(1);
        // Only 1 HTTP request made due to caching
        verify(mockHttp, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("Should return empty list without HTTP call when Tavily API key is missing")
    void shouldReturnEmptyWhenUnconfigured() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        TavilyConfig config = new TavilyConfig("https://api.tavily.com", null, 3, Duration.ofSeconds(5));
        DefaultTavilyResearchService service = new DefaultTavilyResearchService(config, mockHttp, objectMapper);

        assertThat(service.isAvailable()).isFalse();
        List<ExternalResearchFinding> findings = service.research("query", "hint");

        assertThat(findings).isEmpty();
        verify(mockHttp, never()).send(any(), any());
    }

    @Test
    @DisplayName("Should mask Tavily API key correctly")
    void shouldMaskApiKeyCorrectly() {
        TavilyConfig config = new TavilyConfig("https://api.tavily.com", "tvly-abcdefghij12345", 3, Duration.ofSeconds(5));
        assertThat(config.maskedApiKey()).isEqualTo("tvly-...2345");
        assertThat(config.maskedApiKey()).doesNotContain("abcdefghij");
    }
}
