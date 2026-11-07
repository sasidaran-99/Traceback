package com.traceback.core.correlation;

import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import com.traceback.core.domain.Relationship;
import com.traceback.core.domain.RelationshipCategory;
import com.traceback.core.domain.RelationshipType;
import com.traceback.core.git.CommitEvidence;

import java.time.Instant;
import java.util.UUID;

/**
 * Factory and builder for creating typed, evidence-backed relationships.
 */
public class RelationshipBuilder {

    public static Relationship buildCommitToErrorRelationship(
            CommitEvidence commit,
            String errorEntityId,
            Instant errorTimestamp,
            RelationshipType type,
            double confidence,
            String explanation,
            String metadata
    ) {
        Event source = new Event(
                UUID.randomUUID(),
                commit.hasConfigurationChanges() ? EventType.CONFIG_CHANGE : EventType.COMMIT,
                EventSource.GITHUB,
                "target-service",
                "production",
                commit.timestamp(),
                commit.commitSha(),
                "{\"message\":\"" + commit.commitMessage().replace("\"", "\\\"") + "\"}"
        );

        Event target = new Event(
                UUID.randomUUID(),
                EventType.ERROR_BURST,
                EventSource.SPRING_BOOT,
                "target-service",
                "production",
                errorTimestamp != null ? errorTimestamp : Instant.now(),
                errorEntityId,
                null
        );

        return new Relationship(
                UUID.randomUUID(),
                source,
                target,
                type,
                confidence,
                explanation,
                metadata
        );
    }
}
