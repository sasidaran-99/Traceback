package com.traceback.core.agent.tavily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Representation of an external technical research finding retrieved from Tavily.
 * Kept separate from internal repository EvidenceItem entities to prevent conflation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalResearchFinding(
        String findingId,
        String query,
        String title,
        String url,
        String snippet,
        Instant retrievedAt,
        double relevanceScore
) {
    public static ExternalResearchFinding create(String query, String title, String url, String snippet, double score) {
        String id = "RES-TAVILY-" + UUID.randomUUID().toString().substring(0, 8);
        return new ExternalResearchFinding(id, query, title, url, snippet, Instant.now(), score);
    }
}
