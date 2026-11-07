package com.traceback.core.domain;

/**
 * Epistemological classification of relationships in Traceback.
 * <p>
 * Explicit relationships are deterministic and cryptographically or structurally verifiable.
 * Inferred relationships are synthesized from semantic, syntactic, or configuration overlaps.
 * Temporal relationships represent chronological proximity without structural proof.
 */
public enum RelationshipCategory {
    EXPLICIT,
    INFERRED,
    TEMPORAL
}
