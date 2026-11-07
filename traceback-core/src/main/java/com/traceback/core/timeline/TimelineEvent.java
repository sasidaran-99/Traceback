package com.traceback.core.timeline;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * A discrete point-in-time occurrence on the reconstructed investigation timeline.
 */
public record TimelineEvent(
        String eventId,
        Instant timestamp,
        String eventType,
        String source,
        String entityId,
        String title,
        String description,
        boolean isPreFailure,
        String evidenceId,
        Map<String, Object> metadata
) {
    public TimelineEvent {
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
    }
}
