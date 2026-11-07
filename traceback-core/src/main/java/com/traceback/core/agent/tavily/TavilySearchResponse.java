package com.traceback.core.agent.tavily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response schema returned by Tavily search endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TavilySearchResponse(
        String query,
        String answer,
        List<TavilySearchResult> results,
        Double response_time
) {}
