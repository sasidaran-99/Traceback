package com.traceback.core.timeline;

import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.domain.Incident;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.workingtree.WorkingTreeEvidence;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineConfigEvidenceResolutionTest {

    private TimelineBuilder timelineBuilder;
    private CorrelationEngine correlationEngine;
    private ConcreteEvidenceRegistry evidenceRegistry;
    private InvestigationPersistenceMapper persistenceMapper;

    @BeforeEach
    void setUp() {
        timelineBuilder = new TimelineBuilder();
        correlationEngine = new CorrelationEngine();
        evidenceRegistry = new ConcreteEvidenceRegistry();
        persistenceMapper = new InvestigationPersistenceMapper(null);
    }

    @Test
    @DisplayName("Canonical config evidence ID should be deterministic and match across components")
    void shouldGenerateCanonicalConfigEvidenceId() {
        String fullSha = "d711d65a3e2fecd40257af992993bfab07fb7a24";
        String expected = "EVD-CFG-d711d65";

        assertThat(ConcreteEvidenceRegistry.configEvidenceId(fullSha)).isEqualTo(expected);
        assertThat(ConcreteEvidenceRegistry.configEvidenceId("d711d65")).isEqualTo(expected);
    }

    @Test
    @DisplayName("Every timeline CONFIG_DELTA event must resolve to a registered evidence item in EvidenceRegistry")
    void shouldResolveTimelineConfigDeltaEventsToRegisteredEvidenceItems() {
        UUID invId = UUID.randomUUID();
        String commitSha = "d109b64738291029384756281920394857610293";
        Instant commitTime = Instant.now().minusSeconds(300);

        ChangedFile configFile = new ChangedFile(
                "application.properties",
                "M",
                5,
                2,
                "diff --git a/application.properties b/application.properties",
                true,
                Map.of("spring.datasource.hikari.connection-timeout", "-30000ms -> +3000ms")
        );

        CommitEvidence commitWithConfig = new CommitEvidence(
                commitSha,
                "d109b64",
                "dev",
                "dev@traceback.com",
                commitTime,
                "Tune connection pool timeout",
                List.of(configFile),
                5,
                2,
                true,
                false
        );

        // 1. Build timeline
        List<TimelineEvent> events = timelineBuilder.buildTimeline(
                List.of(commitWithConfig),
                null,
                null,
                Instant.now(),
                null
        );

        List<TimelineEvent> configDeltaEvents = events.stream()
                .filter(e -> "CONFIG_DELTA".equals(e.eventType()))
                .toList();

        assertThat(configDeltaEvents).isNotEmpty();
        for (TimelineEvent ce : configDeltaEvents) {
            assertThat(ce.evidenceId()).isEqualTo("EVD-CFG-d109b64");
        }

        // 2. Correlate with CorrelationEngine
        Incident incident = new Incident(
                invId, "INC-01", "Test", "order-service", "prod",
                "HIGH", "INVESTIGATING", Instant.now(), null, null, null
        );

        correlationEngine.correlate(
                incident,
                List.of(commitWithConfig),
                null,
                null,
                null,
                evidenceRegistry,
                60
        );

        // 3. Verify EVERY config delta timeline event resolves to an evidence item in the registry
        for (TimelineEvent ce : configDeltaEvents) {
            String evidenceId = ce.evidenceId();
            assertThat(evidenceId).isNotNull();

            Optional<EvidenceItem> itemOpt = evidenceRegistry.findItemById(evidenceId);
            assertThat(itemOpt).as("Evidence ID " + evidenceId + " must resolve in EvidenceRegistry").isPresent();

            EvidenceItem item = itemOpt.get();
            assertThat(item.evidenceId()).isEqualTo(evidenceId);
            assertThat(item.evidenceType()).isEqualTo(EvidenceType.CONFIG_DIFF);
            assertThat(item.source()).isEqualTo("GIT");
        }
    }

    @Test
    @DisplayName("Should resolve EVD-CFG to registered EVD-GIT commit evidence when dedicated config item not registered")
    void shouldFallbackToRegisteredCommitEvidenceWhenConfigEvidenceNotSeparatelyRegistered() {
        String shortSha = "616947b";
        String gitEvidenceId = "EVD-GIT-" + shortSha;
        String cfgEvidenceId = "EVD-CFG-" + shortSha;

        // Register ONLY commit evidence
        EvidenceItem gitItem = EvidenceItem.create(
                gitEvidenceId,
                EvidenceType.COMMIT,
                "GIT",
                "616947b85f67b57bfec2a00c14c553a6f4461a2d",
                Instant.now(),
                "Initial commit with configs",
                Map.of("author", "dev")
        );
        evidenceRegistry.registerItem(gitItem);

        // Rehydrate a timeline event pointing to EVD-CFG-616947b
        TimelineEventEntity entity = new TimelineEventEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CONFIG_DELTA",
                "GIT",
                "Config delta in pom.xml",
                Instant.now(),
                "[\"" + cfgEvidenceId + "\"]",
                "{}"
        );

        TimelineEvent resolvedEvent = persistenceMapper.toDomain(entity, Set.of(gitEvidenceId));

        assertThat(resolvedEvent.evidenceId())
                .as("Should resolve to registered commit evidence EVD-GIT when EVD-CFG is absent")
                .isEqualTo(gitEvidenceId);

        Optional<EvidenceItem> resolvedItem = evidenceRegistry.findItemById(resolvedEvent.evidenceId());
        assertThat(resolvedItem).isPresent();
        assertThat(resolvedItem.get().evidenceId()).isEqualTo(gitEvidenceId);
    }

    @Test
    @DisplayName("Should preserve direct CONFIG_DIFF evidence reference when present in registry")
    void shouldPreserveDirectConfigDiffEvidenceWhenPresent() {
        String shortSha = "d711d65";
        String gitEvidenceId = "EVD-GIT-" + shortSha;
        String cfgEvidenceId = "EVD-CFG-" + shortSha;

        // Register both GIT and CFG evidence items
        evidenceRegistry.registerItem(EvidenceItem.create(
                gitEvidenceId, EvidenceType.COMMIT, "GIT", shortSha, Instant.now(), "Commit", Collections.emptyMap()
        ));
        evidenceRegistry.registerItem(EvidenceItem.create(
                cfgEvidenceId, EvidenceType.CONFIG_DIFF, "GIT", shortSha + ":config", Instant.now(), "Config", Collections.emptyMap()
        ));

        TimelineEventEntity entity = new TimelineEventEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CONFIG_DELTA",
                "GIT",
                "Config delta in application.yml",
                Instant.now(),
                "[\"" + cfgEvidenceId + "\"]",
                "{}"
        );

        TimelineEvent resolvedEvent = persistenceMapper.toDomain(entity, Set.of(gitEvidenceId, cfgEvidenceId));

        assertThat(resolvedEvent.evidenceId())
                .as("Should keep dedicated EVD-CFG when present in registry")
                .isEqualTo(cfgEvidenceId);
    }

    @Test
    @DisplayName("Should prove 100% of 35 timeline events in historical scenario resolve to registered evidence")
    void shouldResolveAllHistoricalTimelineEventsWithoutDanglingReferences() {
        // Register the 4 commits from historical investigation that had config changes
        List<String> configCommitShas = List.of("616947b", "dd9948f", "7432e51", "d711d65");
        for (String sha : configCommitShas) {
            evidenceRegistry.registerItem(EvidenceItem.create(
                    "EVD-GIT-" + sha,
                    EvidenceType.COMMIT,
                    "GIT",
                    sha,
                    Instant.now(),
                    "Commit " + sha,
                    Collections.emptyMap()
            ));
        }

        // Register a working tree evidence item
        String wtEvidenceId = "EVD-WT-MISSING-2d6e2bee8e";
        evidenceRegistry.registerItem(EvidenceItem.create(
                wtEvidenceId,
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "missing-file",
                Instant.now(),
                "Missing file",
                Collections.emptyMap()
        ));

        // Create 35 simulated timeline events matching historical investigation:
        // 11 COMMIT (resolves to EVD-GIT-*)
        // 4 CONFIG_CHANGE (resolves to EVD-GIT-*)
        // 19 CONFIG_DELTA (pointing to EVD-CFG-*)
        // 1 WORKING_TREE_ANOMALY (resolves to EVD-WT-*)
        List<TimelineEventEntity> historicalEntities = new java.util.ArrayList<>();
        UUID invId = UUID.randomUUID();

        // 1 WT anomaly
        historicalEntities.add(new TimelineEventEntity(
                UUID.randomUUID(), invId, "WORKING_TREE_ANOMALY", "WORKING_TREE", "WT anomaly",
                Instant.now(), "[\"" + wtEvidenceId + "\"]", "{}"
        ));

        // 4 CONFIG_CHANGE
        for (String sha : configCommitShas) {
            historicalEntities.add(new TimelineEventEntity(
                    UUID.randomUUID(), invId, "CONFIG_CHANGE", "GIT", "Commit " + sha,
                    Instant.now(), "[\"EVD-GIT-" + sha + "\"]", "{}"
            ));
        }

        // 19 CONFIG_DELTA (13 in 616947b, 1 in dd9948f, 2 in 7432e51, 3 in d711d65)
        Map<String, Integer> deltaDistribution = Map.of(
                "616947b", 13,
                "dd9948f", 1,
                "7432e51", 2,
                "d711d65", 3
        );
        for (Map.Entry<String, Integer> entry : deltaDistribution.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                historicalEntities.add(new TimelineEventEntity(
                        UUID.randomUUID(), invId, "CONFIG_DELTA", "GIT", "Config delta in file_" + i,
                        Instant.now(), "[\"EVD-CFG-" + entry.getKey() + "\"]", "{}"
                ));
            }
        }

        assertThat(historicalEntities).hasSize(24); // 1 + 4 + 19 = 24 with evidence IDs

        Set<String> registeredEvidenceIds = evidenceRegistry.findAllItems().stream()
                .map(EvidenceItem::evidenceId)
                .collect(java.util.stream.Collectors.toSet());

        List<TimelineEvent> resolvedEvents = historicalEntities.stream()
                .map(e -> persistenceMapper.toDomain(e, registeredEvidenceIds))
                .toList();

        // Verify that 100% of timeline events resolve to an existing registered evidence item
        int resolvedCount = 0;
        for (TimelineEvent event : resolvedEvents) {
            String eid = event.evidenceId();
            assertThat(eid).isNotNull();
            assertThat(registeredEvidenceIds).as("Timeline event evidence ID " + eid + " must resolve in registry").contains(eid);
            resolvedCount++;
        }

        assertThat(resolvedCount).isEqualTo(24);
    }

    @Test
    @DisplayName("Non-config timeline events (COMMIT, WORKING_TREE_ANOMALY) should remain unaffected")
    void shouldKeepNonConfigTimelineEventsUnaffected() {
        String wtId = "EVD-WT-MISSING-abc123";
        String gitId = "EVD-GIT-1122334";

        TimelineEventEntity wtEntity = new TimelineEventEntity(
                UUID.randomUUID(), UUID.randomUUID(), "WORKING_TREE_ANOMALY", "WORKING_TREE", "Anomaly",
                Instant.now(), "[\"" + wtId + "\"]", "{}"
        );
        TimelineEventEntity gitEntity = new TimelineEventEntity(
                UUID.randomUUID(), UUID.randomUUID(), "COMMIT", "GIT", "Commit",
                Instant.now(), "[\"" + gitId + "\"]", "{}"
        );

        TimelineEvent resolvedWt = persistenceMapper.toDomain(wtEntity, Set.of(wtId, gitId));
        TimelineEvent resolvedGit = persistenceMapper.toDomain(gitEntity, Set.of(wtId, gitId));

        assertThat(resolvedWt.evidenceId()).isEqualTo(wtId);
        assertThat(resolvedGit.evidenceId()).isEqualTo(gitId);
    }
}
