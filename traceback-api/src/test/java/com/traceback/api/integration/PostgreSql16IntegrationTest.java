package com.traceback.api.integration;

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
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying full database capabilities against a live PostgreSQL 16+ instance.
 * Covers Flyway migrations, connection pooling via HikariCP, JSONB metadata,
 * foreign key cascade constraints, unique constraints, and transaction rollback.
 */
@ExtendWith(PostgreSqlAvailableCondition.class)
@SpringBootTest
@ActiveProfiles("postgres-test")
class PostgreSql16IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private RelationshipRepository relationshipRepository;

    @Autowired
    private HypothesisRepository hypothesisRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        hypothesisRepository.deleteAll();
        relationshipRepository.deleteAll();
        incidentRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("Should verify live PostgreSQL database connection and HikariCP pool")
    void shouldVerifyPostgreSqlConnection() throws Exception {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikariDs = (HikariDataSource) dataSource;
        assertThat(hikariDs.getJdbcUrl()).contains("postgresql");

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            assertThat(metaData.getDatabaseProductName()).containsIgnoringCase("PostgreSQL");
            int majorVersion = metaData.getDatabaseMajorVersion();
            assertThat(majorVersion).isGreaterThanOrEqualTo(16);
        }
    }

    @Test
    @DisplayName("Should apply Flyway migrations and create all core tables with JSONB columns")
    void shouldVerifyFlywaySchemaAndTables() throws Exception {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        List<String> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }
        }

        assertThat(tables).contains("events", "incidents", "event_relationships", "hypotheses");

        // Verify JSONB data type in information_schema
        Integer jsonbColumnsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND data_type = 'jsonb'",
                Integer.class
        );
        assertThat(jsonbColumnsCount).isNotNull().isGreaterThanOrEqualTo(3); // events.metadata, incidents.metadata, event_relationships.metadata
    }

    @Test
    @DisplayName("Should persist and query events with native PostgreSQL JSONB metadata")
    void shouldPersistAndQueryEventsWithJsonb() {
        Instant now = Instant.now();
        String jsonbMeta = "{\"author\":\"developer\",\"commit_sha\":\"a1b2c3d\",\"files\":[\"OrderService.java\",\"pom.xml\"],\"diff_stats\":{\"added\":42,\"deleted\":7}}";

        Event commitEvent = new Event(
                UUID.randomUUID(),
                EventType.COMMIT,
                EventSource.GITHUB,
                "order-service",
                "production",
                now.minus(10, ChronoUnit.MINUTES),
                "commit-pg-001",
                jsonbMeta
        );

        Event deployEvent = new Event(
                UUID.randomUUID(),
                EventType.DEPLOYMENT,
                EventSource.KUBERNETES,
                "order-service",
                "production",
                now.minus(5, ChronoUnit.MINUTES),
                "deploy-pg-001",
                "{\"version\":\"v2.4.1\",\"pod_count\":5}"
        );

        eventRepository.save(commitEvent);
        eventRepository.save(deployEvent);

        // Verify temporal window query
        List<Event> windowEvents = eventRepository.findByServiceNameAndEnvironmentAndTimestampBetweenOrderByTimestampDesc(
                "order-service",
                "production",
                now.minus(15, ChronoUnit.MINUTES),
                now
        );
        assertThat(windowEvents).hasSize(2);
        assertThat(windowEvents.get(0).getEntityId()).isEqualTo("deploy-pg-001");
        assertThat(windowEvents.get(1).getEntityId()).isEqualTo("commit-pg-001");

        // Verify JSONB metadata preservation (PostgreSQL jsonb normalizes whitespace and key ordering)
        Event retrieved = eventRepository.findById(commitEvent.getId()).orElseThrow();
        assertThat(retrieved.getMetadata())
                .contains("commit_sha")
                .contains("a1b2c3d")
                .contains("added")
                .contains("42");

        // Verify entity ID query
        List<Event> byEntity = eventRepository.findByEntityId("commit-pg-001");
        assertThat(byEntity).hasSize(1);
        assertThat(byEntity.get(0).getServiceName()).isEqualTo("order-service");
    }

    @Test
    @DisplayName("Should enforce UNIQUE constraint on incident_key in PostgreSQL")
    void shouldEnforceUniqueConstraintOnIncidentKey() {
        Event trigger = eventRepository.save(new Event(
                UUID.randomUUID(),
                EventType.ERROR_BURST,
                EventSource.SPRING_BOOT,
                "auth-service",
                "production",
                Instant.now(),
                "err-pg-unique",
                null
        ));

        Incident inc1 = new Incident(
                UUID.randomUUID(),
                "INC-DUPLICATE-KEY",
                "First Incident",
                "auth-service",
                "production",
                "HIGH",
                "OPEN",
                Instant.now(),
                null,
                trigger,
                "{\"detail\":\"first\"}"
        );
        incidentRepository.saveAndFlush(inc1);

        Incident inc2 = new Incident(
                UUID.randomUUID(),
                "INC-DUPLICATE-KEY", // Same unique key
                "Second Incident",
                "auth-service",
                "production",
                "HIGH",
                "OPEN",
                Instant.now(),
                null,
                trigger,
                "{\"detail\":\"duplicate\"}"
        );

        assertThatThrownBy(() -> incidentRepository.saveAndFlush(inc2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should persist relationships and cascade delete on event deletion")
    void shouldPersistRelationshipAndCascadeOnEventDelete() {
        Instant now = Instant.now();
        Event source = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.DEPLOYMENT, EventSource.KUBERNETES,
                "billing-service", "production", now.minus(5, ChronoUnit.MINUTES), "deploy-casc-1", null
        ));
        Event target = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB,
                "billing-service", "production", now.minus(10, ChronoUnit.MINUTES), "commit-casc-1", null
        ));

        Relationship rel = new Relationship(
                UUID.randomUUID(),
                source,
                target,
                RelationshipType.EXPLICIT_DEPLOY_COMMIT,
                1.0,
                "Explicit commit deployed",
                "{\"source\":\"git log\"}"
        );
        Relationship savedRel = relationshipRepository.saveAndFlush(rel);
        assertThat(savedRel.getId()).isNotNull();

        List<Relationship> rels = relationshipRepository.findBySourceEventId(source.getId());
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).getCategory()).isEqualTo(RelationshipCategory.EXPLICIT);

        // Delete target event directly from repository (testing PostgreSQL cascade constraint via jdbcTemplate)
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", target.getId());

        // Relationship should have been deleted by ON DELETE CASCADE
        List<Relationship> afterCascade = relationshipRepository.findBySourceEventId(source.getId());
        assertThat(afterCascade).isEmpty();
    }

    @Test
    @DisplayName("Should persist hypotheses, maintain rank order, and cascade delete on incident deletion")
    void shouldPersistHypothesesAndCascadeOnIncidentDelete() {
        Event trigger = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.ERROR_BURST, EventSource.SPRING_BOOT,
                "payment-service", "production", Instant.now(), "err-hypo-1", null
        ));
        Event cand1 = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB,
                "payment-service", "production", Instant.now().minus(5, ChronoUnit.MINUTES), "cand-hypo-1", null
        ));
        Event cand2 = eventRepository.save(new Event(
                UUID.randomUUID(), EventType.CONFIG_CHANGE, EventSource.KUBERNETES,
                "payment-service", "production", Instant.now().minus(8, ChronoUnit.MINUTES), "cand-hypo-2", null
        ));

        Incident incident = incidentRepository.saveAndFlush(new Incident(
                UUID.randomUUID(),
                "INC-HYPO-TEST",
                "Checkout Failure",
                "payment-service",
                "production",
                "CRITICAL",
                "INVESTIGATING",
                Instant.now(),
                null,
                trigger,
                null
        ));

        Hypothesis h1 = new Hypothesis(
                UUID.randomUUID(),
                incident,
                cand1,
                1,
                0.92,
                HypothesisStatus.PROPOSED,
                "Likely NPE introduced in commit cand-hypo-1",
                "Inspect PaymentGateway.java diff",
                "None"
        );

        Hypothesis h2 = new Hypothesis(
                UUID.randomUUID(),
                incident,
                cand2,
                2,
                0.65,
                HypothesisStatus.INVESTIGATING,
                "Config timeout reduction may cause timeout",
                "Inspect config map",
                "Timeout logs not yet seen"
        );

        hypothesisRepository.saveAndFlush(h1);
        hypothesisRepository.saveAndFlush(h2);

        List<Hypothesis> hypotheses = hypothesisRepository.findByIncidentIdOrderByRankAsc(incident.getId());
        assertThat(hypotheses).hasSize(2);
        assertThat(hypotheses.get(0).getRank()).isEqualTo(1);
        assertThat(hypotheses.get(0).getConfidenceScore()).isEqualTo(0.92);
        assertThat(hypotheses.get(1).getRank()).isEqualTo(2);
        assertThat(hypotheses.get(1).getConfidenceScore()).isEqualTo(0.65);

        // Delete incident via SQL to verify PostgreSQL cascade delete
        jdbcTemplate.update("DELETE FROM incidents WHERE id = ?", incident.getId());

        List<Hypothesis> remaining = hypothesisRepository.findByIncidentIdOrderByRankAsc(incident.getId());
        assertThat(remaining).isEmpty();
    }

    @Test
    @DisplayName("Should verify transaction rollback does not commit changes to PostgreSQL")
    void shouldRollbackTransactionCleanly() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            Event event = new Event(
                    UUID.randomUUID(),
                    EventType.COMMIT,
                    EventSource.GITHUB,
                    "order-service",
                    "production",
                    Instant.now(),
                    "rollback-commit-test",
                    null
            );
            eventRepository.save(event);
            throw new RuntimeException("Simulated transaction failure to trigger rollback");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated transaction failure");

        List<Event> found = eventRepository.findByEntityId("rollback-commit-test");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should verify data persistence across multiple independent transactions")
    void shouldPersistDataAcrossTransactions() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        UUID eventId = txTemplate.execute(status -> {
            Event event = new Event(
                    UUID.randomUUID(),
                    EventType.COMMIT,
                    EventSource.GITHUB,
                    "order-service",
                    "production",
                    Instant.now(),
                    "persistent-commit-tx",
                    "{\"status\":\"committed\"}"
            );
            return eventRepository.save(event).getId();
        });

        assertThat(eventId).isNotNull();

        // Verify in a second independent transaction
        txTemplate.executeWithoutResult(status -> {
            Optional<Event> retrieved = eventRepository.findById(eventId);
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get().getEntityId()).isEqualTo("persistent-commit-tx");
        });
    }
}
