package com.traceback.core.agent.tavily;

import java.util.List;

/**
 * Service for retrieving external technical research findings via Tavily.
 */
public interface TavilyResearchService {
    /**
     * Conducts technical research for an error signature or library issue.
     *
     * @param query the search query
     * @param contextHint optional domain context (e.g., "Spring Boot", "HikariCP")
     * @return list of external research findings with citations
     */
    List<ExternalResearchFinding> research(String query, String contextHint);

    /**
     * Checks if Tavily API is configured with a valid key.
     */
    boolean isAvailable();

    /**
     * Returns active configuration.
     */
    TavilyConfig getConfig();
}
