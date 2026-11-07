package com.traceback.core.agent.tavily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Individual result item from Tavily search response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TavilySearchResult(
        String title,
        String url,
        String content,
        Double score,
        String raw_content
) {}
