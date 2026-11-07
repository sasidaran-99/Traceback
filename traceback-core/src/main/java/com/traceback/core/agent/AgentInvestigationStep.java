package com.traceback.core.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Record representing an auditable action taken by the investigation agent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentInvestigationStep(
        int stepNumber,
        String stepName,
        String description,
        Instant timestamp,
        Map<String, Object> metadata
) {
    public AgentInvestigationStep {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
    }

    public static AgentInvestigationStep of(int stepNumber, String stepName, String description) {
        return new AgentInvestigationStep(stepNumber, stepName, description, Instant.now(), Collections.emptyMap());
    }

    public static AgentInvestigationStep of(int stepNumber, String stepName, String description, Map<String, Object> metadata) {
        return new AgentInvestigationStep(stepNumber, stepName, description, Instant.now(), metadata);
    }
}
