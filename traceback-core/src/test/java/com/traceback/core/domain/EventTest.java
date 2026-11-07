package com.traceback.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventTest {

    @Test
    @DisplayName("Should create event with explicit values")
    void shouldCreateEventWithExplicitValues() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Event event = new Event(
                id,
                EventType.COMMIT,
                EventSource.GITHUB,
                "payment-service",
                "production",
                now,
                "c1a89b3",
                "{\"author\":\"alice\"}"
        );

        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getEventType()).isEqualTo(EventType.COMMIT);
        assertThat(event.getSource()).isEqualTo(EventSource.GITHUB);
        assertThat(event.getServiceName()).isEqualTo("payment-service");
        assertThat(event.getEnvironment()).isEqualTo("production");
        assertThat(event.getTimestamp()).isEqualTo(now);
        assertThat(event.getEntityId()).isEqualTo("c1a89b3");
        assertThat(event.getMetadata()).isEqualTo("{\"author\":\"alice\"}");
    }

    @Test
    @DisplayName("Should auto-generate UUID and timestamps on PrePersist")
    void shouldAutoGenerateIdAndTimestampsOnPrePersist() {
        Event event = new Event();
        event.setEventType(EventType.ERROR_BURST);
        event.setSource(EventSource.SPRING_BOOT);
        event.setServiceName("payment-service");
        event.setEnvironment("production");
        event.setTimestamp(Instant.now());
        event.setEntityId("err-991");

        event.onCreate();

        assertThat(event.getId()).isNotNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should maintain equality based on ID")
    void shouldMaintainEqualityBasedOnId() {
        UUID id = UUID.randomUUID();
        Event e1 = new Event(id, EventType.COMMIT, EventSource.GITHUB, "svc", "prod", Instant.now(), "sha1", null);
        Event e2 = new Event(id, EventType.DEPLOYMENT, EventSource.KUBERNETES, "svc", "prod", Instant.now(), "dep1", null);

        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }
}
