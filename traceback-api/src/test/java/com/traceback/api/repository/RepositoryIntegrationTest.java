package com.traceback.api.repository;

import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.domain.Relationship;
import com.traceback.core.domain.RelationshipCategory;
import com.traceback.core.domain.RelationshipType;
import com.traceback.core.repository.EventRepository;
import com.traceback.core.repository.HypothesisRepository;
import com.traceback.core.repository.IncidentRepository;
import com.traceback.core.repository.RelationshipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryIntegrationTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private RelationshipRepository relationshipRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private HypothesisRepository hypothesisRepository;

    @BeforeEach
    void setUp() {
        hypothesisRepository.deleteAll();
        incidentRepository.deleteAll();
        relationshipRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("Should persist events and query by window, entityId, and type")
    void shouldPersistAndQueryEvents() {
        Instant now = Instant.now();
        Event commit = new Event(
                UUID.randomUUID(),
                EventType.COMMIT,
                EventSource.GITHUB,
                "payment-service",
                "production",
                now.minus(10, ChronoUnit.MINUTES),
                "sha-test-c1a89b3",
                "{\"author\":\"alice\"}"
        );
        Event deploy = new Event(
                UUID.randomUUID(),
                EventType.DEPLOYMENT,
                EventSource.KUBERNETES,
                "payment-service",
                "production",
                now.minus(5, ChronoUnit.MINUTES),
                "dep-test-102",
                "{\"image\":\"payment:v1.2\"}"
        );

        eventRepository.save(commit);
        eventRepository.save(deploy);

        // Query by window
        List<Event> windowEvents = eventRepository.findByServiceNameAndEnvironmentAndTimestampBetweenOrderByTimestampDesc(
                "payment-service",
                "production",
                now.minus(15, ChronoUnit.MINUTES),
                now
        );
        assertThat(windowEvents).hasSize(2);
        assertThat(windowEvents.get(0).getEntityId()).isEqualTo("dep-test-102");
        assertThat(windowEvents.get(1).getEntityId()).isEqualTo("sha-test-c1a89b3");

        // Query by entityId
        List<Event> byEntityId = eventRepository.findByEntityId("sha-test-c1a89b3");
        assertThat(byEntityId).hasSize(1);
        assertThat(byEntityId.get(0).getEventType()).isEqualTo(EventType.COMMIT);

        // Query by eventType
        List<Event> deploys = eventRepository.findByEventType(EventType.DEPLOYMENT);
        assertThat(deploys).extracting(Event::getEntityId).contains("dep-test-102");
    }

    @Test
    @DisplayName("Should persist relationships between events with category and confidence")
    void shouldPersistEventRelationships() {
        Event commit = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB, "payment-service", "production", Instant.now(), "sha-rel-1", null));
        Event deploy = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.DEPLOYMENT, EventSource.KUBERNETES, "payment-service", "production", Instant.now(), "dep-rel-1", null));

        Relationship rel = new Relationship(
                UUID.randomUUID(),
                deploy,
                commit,
                RelationshipType.EXPLICIT_DEPLOY_COMMIT,
                1.0,
                "Deployment explicitly contains commit",
                "{\"sha\":\"sha-rel-1\"}"
        );

        Relationship savedRel = relationshipRepository.save(rel);
        assertThat(savedRel.getId()).isNotNull();
        assertThat(savedRel.getCategory()).isEqualTo(RelationshipCategory.EXPLICIT);

        List<Relationship> sourceRels = relationshipRepository.findBySourceEventId(deploy.getId());
        assertThat(sourceRels).hasSize(1);
        assertThat(sourceRels.get(0).getTargetEvent().getId()).isEqualTo(commit.getId());

        List<Relationship> explicitRels = relationshipRepository.findByCategory(RelationshipCategory.EXPLICIT);
        assertThat(explicitRels).extracting(Relationship::getId).contains(savedRel.getId());
    }

    @Test
    @DisplayName("Should persist incident and hypotheses, maintaining rank order")
    void shouldPersistIncidentAndHypotheses() {
        Event error = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.ERROR_BURST, EventSource.SPRING_BOOT, "payment-service", "production", Instant.now(), "err-inc-001", null));
        Event commit = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB, "payment-service", "production", Instant.now().minus(5, ChronoUnit.MINUTES), "sha-inc-1", null));

        Incident incident = new Incident(
                UUID.randomUUID(),
                "INC-TEST-001",
                "Payment Math Regression",
                "payment-service",
                "production",
                "HIGH",
                "INVESTIGATING",
                Instant.now(),
                null,
                error,
                "{\"impact\":\"checkout failure\"}"
        );
        Incident savedIncident = incidentRepository.save(incident);

        Hypothesis h1 = new Hypothesis(
                UUID.randomUUID(),
                savedIncident,
                commit,
                1,
                0.95,
                HypothesisStatus.PROPOSED,
                "Code regression in fee calculator line 58",
                "Inspect git diff for sha-inc-1",
                "None"
        );
        hypothesisRepository.save(h1);

        Optional<Incident> found = incidentRepository.findByIncidentKey("INC-TEST-001");
        assertThat(found).isPresent();
        assertThat(found.get().getTriggeringEvent().getId()).isEqualTo(error.getId());

        List<Hypothesis> hypotheses = hypothesisRepository.findByIncidentIdOrderByRankAsc(savedIncident.getId());
        assertThat(hypotheses).hasSize(1);
        assertThat(hypotheses.get(0).getRank()).isEqualTo(1);
        assertThat(hypotheses.get(0).getConfidenceScore()).isEqualTo(0.95);
        assertThat(hypotheses.get(0).getCandidateRootCause().getEntityId()).isEqualTo("sha-inc-1");
    }
}
