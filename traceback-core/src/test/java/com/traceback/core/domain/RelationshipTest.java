package com.traceback.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationshipTest {

    @Test
    @DisplayName("Should assign correct category and default confidence based on relationship type")
    void shouldAssignCorrectCategoryAndDefaultConfidence() {
        Event source = new Event(UUID.randomUUID(), EventType.DEPLOYMENT, EventSource.KUBERNETES, "payment-service", "prod", Instant.now(), "dep-102", null);
        Event target = new Event(UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB, "payment-service", "prod", Instant.now(), "c1a89b3", null);

        Relationship relExplicit = new Relationship(null, source, target, RelationshipType.EXPLICIT_DEPLOY_COMMIT, null, null, null);
        assertThat(relExplicit.getCategory()).isEqualTo(RelationshipCategory.EXPLICIT);
        assertThat(relExplicit.getConfidenceWeight()).isEqualTo(1.0);
        assertThat(relExplicit.isExplicit()).isTrue();
        assertThat(relExplicit.isInferred()).isFalse();

        Relationship relInferred = new Relationship(null, target, source, RelationshipType.INFERRED_CODE_FRAME, null, null, null);
        assertThat(relInferred.getCategory()).isEqualTo(RelationshipCategory.INFERRED);
        assertThat(relInferred.getConfidenceWeight()).isEqualTo(0.95);
        assertThat(relInferred.isInferred()).isTrue();

        Relationship relTemporal = new Relationship(null, source, target, RelationshipType.TEMPORAL_PRECEDENCE, null, null, null);
        assertThat(relTemporal.getCategory()).isEqualTo(RelationshipCategory.TEMPORAL);
        assertThat(relTemporal.getConfidenceWeight()).isEqualTo(0.30);
        assertThat(relTemporal.isTemporal()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, -1.0, 1.01, 2.5})
    @DisplayName("Should reject invalid confidence weight outside [0.0, 1.0]")
    void shouldRejectInvalidConfidenceWeight(double invalidWeight) {
        Event source = new Event();
        Event target = new Event();

        assertThatThrownBy(() -> new Relationship(null, source, target, RelationshipType.INFERRED_CODE_FRAME, invalidWeight, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 1.0");
    }
}
