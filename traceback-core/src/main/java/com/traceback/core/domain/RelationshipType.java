package com.traceback.core.domain;

/**
 * Typed relationship between two events in the Traceback evidence graph.
 */
public enum RelationshipType {
    // Explicit deterministic links
    EXPLICIT_DEPLOY_COMMIT(RelationshipCategory.EXPLICIT, 1.0, "Deployment manifest explicitly references commit SHA"),
    EXPLICIT_CI_COMMIT(RelationshipCategory.EXPLICIT, 1.0, "CI build ran against commit SHA"),
    EXPLICIT_CONFIG_APPLY(RelationshipCategory.EXPLICIT, 1.0, "Restart or deployment applied specific configuration revision"),

    // Inferred structural/semantic links
    INFERRED_CODE_FRAME(RelationshipCategory.INFERRED, 0.95, "Stack trace frame matches modified file and line in commit diff"),
    INFERRED_CONFIG_KEY(RelationshipCategory.INFERRED, 0.90, "Configuration key matches token or error message in exception"),
    INFERRED_EXTERNAL_DEPENDENCY(RelationshipCategory.INFERRED, 0.85, "External dependency hostname or endpoint matches error target"),

    // Temporal precedence links
    TEMPORAL_PRECEDENCE(RelationshipCategory.TEMPORAL, 0.30, "Event occurred chronologically prior to failure within pre-incident window");

    private final RelationshipCategory category;
    private final double defaultConfidence;
    private final String description;

    RelationshipType(RelationshipCategory category, double defaultConfidence, String description) {
        this.category = category;
        this.defaultConfidence = defaultConfidence;
        this.description = description;
    }

    public RelationshipCategory getCategory() {
        return category;
    }

    public double getDefaultConfidence() {
        return defaultConfidence;
    }

    public String getDescription() {
        return description;
    }

    public boolean isExplicit() {
        return category == RelationshipCategory.EXPLICIT;
    }

    public boolean isInferred() {
        return category == RelationshipCategory.INFERRED;
    }

    public boolean isTemporal() {
        return category == RelationshipCategory.TEMPORAL;
    }
}
