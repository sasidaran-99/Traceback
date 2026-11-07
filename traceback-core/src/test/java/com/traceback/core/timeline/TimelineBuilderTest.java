package com.traceback.core.timeline;

import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.log.LogEntry;
import com.traceback.core.log.LogEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineBuilderTest {

    private final TimelineBuilder builder = new TimelineBuilder();

    @Test
    @DisplayName("Should build strictly chronological timeline and tag pre/post failure events")
    void shouldBuildChronologicalTimeline() {
        Instant t0 = Instant.parse("2026-10-10T14:10:00Z"); // commit 1
        Instant t1 = Instant.parse("2026-10-10T14:12:00Z"); // commit 2 (config)
        Instant tFailure = Instant.parse("2026-10-10T14:16:30Z"); // first error
        Instant tPost = Instant.parse("2026-10-10T14:20:00Z"); // post-failure commit

        ChangedFile cf = new ChangedFile("application.properties", "MODIFY", 1, 1, "+timeout=3000\n-timeout=30000", true, Map.of("timeout", "30000 => 3000"));

        CommitEvidence c1 = new CommitEvidence("sha-1", "sha1", "Alice", "alice@example.com", t0, "Initial commit", Collections.emptyList(), 0, 0, false, false);
        CommitEvidence c2 = new CommitEvidence("sha-2", "sha2", "Bob", "bob@example.com", t1, "Reduce timeout", List.of(cf), 1, 1, true, false);
        CommitEvidence c3 = new CommitEvidence("sha-3", "sha3", "Dave", "dave@example.com", tPost, "Post-outage commit", Collections.emptyList(), 0, 0, false, false);

        LogEntry err = new LogEntry(tFailure, "ERROR", "main", "c.t.Payment", "Connection timeout", null, null, 42);
        LogEvidence logEvidence = new LogEvidence(tFailure, tFailure, 1, 0, List.of(err), Collections.emptyMap(), null, null);

        List<TimelineEvent> timeline = builder.buildTimeline(List.of(c1, c2, c3), logEvidence, null, tFailure);

        // Verify ordering: t0 <= t1 <= tFailure <= tPost
        assertThat(timeline).isSortedAccordingTo((e1, e2) -> e1.timestamp().compareTo(e2.timestamp()));

        // Verify pre/post tagging
        TimelineEvent firstEvent = timeline.get(0);
        assertThat(firstEvent.timestamp()).isEqualTo(t0);
        assertThat(firstEvent.isPreFailure()).isTrue();

        TimelineEvent lastEvent = timeline.get(timeline.size() - 1);
        assertThat(lastEvent.timestamp()).isEqualTo(tPost);
        assertThat(lastEvent.isPreFailure()).isFalse();

        // Verify config delta event was generated
        assertThat(timeline).anyMatch(e -> "CONFIG_DELTA".equals(e.eventType()) && e.entityId().contains("application.properties"));
    }
}
