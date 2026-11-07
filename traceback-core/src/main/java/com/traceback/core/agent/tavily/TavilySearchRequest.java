package com.traceback.core.agent.tavily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request payload for Tavily search endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TavilySearchRequest(
        String api_key,
        String query,
        String search_depth,
        Integer max_results,
        Boolean include_answer
) {
    public static TavilySearchRequest of(String apiKey, String query, int maxResults) {
        return new TavilySearchRequest(apiKey, query, "basic", maxResults, true);
    }
}
