package com.traceback.core.agent;

/**
 * High-level investigation verdict determined by the AI agent grounded in deterministic confidence.
 */
public enum AgentInvestigationStatus {
    /**
     * Strongly supported by verified evidence with zero or refuted contradictions.
     */
    CONFIRMED,

    /**
     * High probability root cause identified, but some factors require manual verification.
     */
    PROBABLE,

    /**
     * Available evidence is insufficient, inconclusive, or heavily contradicted.
     */
    INSUFFICIENT_EVIDENCE
}
