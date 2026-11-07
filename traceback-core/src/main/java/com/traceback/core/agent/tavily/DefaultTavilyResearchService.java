package com.traceback.core.agent.tavily;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link TavilyResearchService} using Java 21's {@link HttpClient}.
 */
public class DefaultTavilyResearchService implements TavilyResearchService {
    private static final Logger log = LoggerFactory.getLogger(DefaultTavilyResearchService.class);

    private final TavilyConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI searchEndpoint;
    private final Map<String, List<ExternalResearchFinding>> queryCache = new ConcurrentHashMap<>();

    public DefaultTavilyResearchService(TavilyConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build(), new ObjectMapper());
    }

    public DefaultTavilyResearchService(TavilyConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = config != null ? config : TavilyConfig.fromEnvironment();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.searchEndpoint = resolveEndpoint(this.config.baseUrl());
    }

    private static URI resolveEndpoint(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/search")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed + "/search");
    }

    @Override
    public boolean isAvailable() {
        return config.isConfigured();
    }

    @Override
    public TavilyConfig getConfig() {
        return config;
    }

    @Override
    public List<ExternalResearchFinding> research(String query, String contextHint) {
        if (!isAvailable()) {
            log.info("Tavily research requested but API key is not configured. Returning empty findings.");
            return Collections.emptyList();
        }

        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String fullQuery = query.trim();
        if (contextHint != null && !contextHint.isBlank() && !fullQuery.contains(contextHint.trim())) {
            fullQuery = fullQuery + " " + contextHint.trim();
        }

        // Check query cache
        if (queryCache.containsKey(fullQuery)) {
            log.debug("Returning cached Tavily research findings for query: {}", fullQuery);
            return queryCache.get(fullQuery);
        }

        try {
            TavilySearchRequest requestPayload = TavilySearchRequest.of(
                    config.apiKey(),
                    fullQuery,
                    Math.min(config.maxSearchesPerInvestigation(), 5)
            );

            String requestJson = objectMapper.writeValueAsString(requestPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(searchEndpoint)
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            log.debug("Dispatching search query to Tavily at {} (key={}): {}",
                    searchEndpoint, config.maskedApiKey(), fullQuery);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                TavilySearchResponse parsed = objectMapper.readValue(response.body(), TavilySearchResponse.class);
                List<ExternalResearchFinding> findings = new ArrayList<>();

                if (parsed.results() != null) {
                    for (TavilySearchResult res : parsed.results()) {
                        findings.add(ExternalResearchFinding.create(
                                fullQuery,
                                res.title() != null ? res.title() : "External Technical Reference",
                                res.url() != null ? res.url() : "",
                                res.content() != null ? res.content() : "",
                                res.score() != null ? res.score() : 0.8
                        ));
                    }
                }

                queryCache.put(fullQuery, List.copyOf(findings));
                return findings;
            }

            throw new TavilyException("Tavily API responded with error: " + response.body(), status);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TavilyException("Failed to execute Tavily technical research query: " + e.getMessage(), e);
        }
    }
}
