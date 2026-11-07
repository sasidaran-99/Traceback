package com.traceback.core.evidence;

import com.traceback.core.domain.RelationshipCategory;
import com.traceback.core.domain.RelationshipType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable claim of evidence connecting two events or attributing a symptom to a root cause.
 */
public record EvidenceClaim(
        UUID claimId,
        UUID sourceEventId,
        UUID targetEventId,
        RelationshipType relationshipType,
        RelationshipCategory category,
        double confidenceWeight,
        String explanation,
        Map<String, Object> metadata,
        Instant assertedAt
) {
    public EvidenceClaim {
        if (claimId == null) {
            claimId = UUID.randomUUID();
        }
        if (assertedAt == null) {
            assertedAt = Instant.now();
        }
        if (relationshipType != null && category == null) {
            category = relationshipType.getCategory();
        }
    }

    public static EvidenceClaim create(UUID sourceEventId, UUID targetEventId,
                                       RelationshipType type, double confidence,
                                       String explanation, Map<String, Object> metadata) {
        return new EvidenceClaim(
                UUID.randomUUID(),
                sourceEventId,
                targetEventId,
                type,
                type != null ? type.getCategory() : RelationshipCategory.TEMPORAL,
                confidence,
                explanation,
                metadata,
                Instant.now()
        );
    }
}
