package com.traceback.api.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.integration.PostgreSqlAvailableCondition;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.InvestigationHypothesisEntity;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import com.traceback.core.timeline.TimelineEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PostgreSqlAvailableCondition.class)
@SpringBootTest
@ActiveProfiles("postgres-test")
class Phase921PersistenceRehydrationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired(required = false)
    private ActiveInvestigationBootstrapService bootstrapService;

    @org.junit.jupiter.api.BeforeEach
    void verifyDatabaseIsolation() {
        String dbUrl = environment.getProperty("spring.datasource.url", "");
        if (dbUrl.toLowerCase().contains("traceback_db")) {
            throw new IllegalStateException(
                "CRITICAL SAFETY VIOLATION: Integration tests must NEVER connect to production/active database 'traceback_db'! Current URL=" + dbUrl
            );
        }
        assertThat(dbUrl).contains("traceback_test_db");

        if (bootstrapService != null && investigationRepository.findById(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID).isEmpty()) {
            java.nio.file.Path p1 = java.nio.file.Path.of("fixtures", "complete_active_investigation_backup.json");
            java.nio.file.Path p2 = java.nio.file.Path.of("..", "fixtures", "complete_active_investigation_backup.json");
            java.nio.file.Path p3 = java.nio.file.Path.of("C:\\Users\\Sasidaran\\.gemini\\antigravity\\brain\\312124e7-6010-40db-ad58-48917858073c\\scratch\\complete_active_investigation_backup.json");
            java.nio.file.Path backupPath = java.nio.file.Files.exists(p1) ? p1 : (java.nio.file.Files.exists(p2) ? p2 : p3);
            if (java.nio.file.Files.exists(backupPath)) {
                bootstrapService.importFromBackup(backupPath, false);
            }
        }
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private InvestigationRepository investigationRepository;

    @Autowired
    private InvestigationEvidenceRepository evidenceRepository;

    @Autowired
    private InvestigationHypothesisRepository hypothesisRepository;

    @Autowired
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired
    private InvestigationTimelineRepository timelineRepository;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private InvestigationPersistenceMapper persistenceMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Flyway V3+ migration should apply successfully on PostgreSQL 16+ with new agent report columns")
    void shouldVerifyFlywayV3AppliedWithAgentReportColumns() throws Exception {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");

        List<String> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, "public", "investigation_agent_reports", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
        }

        assertThat(columns).contains(
                "primary_suspected_root_cause",
                "supporting_evidence_ids",
                "contradictory_evidence_ids"
        );
    }

    @Test
    @DisplayName("Should persist and rehydrate Agent Report with primary root-cause and evidence collections on PostgreSQL")
    @Transactional
    void shouldPersistAndRehydrateAgentReportOnPostgreSql() throws Exception {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);
        String rootCause = "Test Root Cause: Connection timeout due to misconfigured pool";
        List<String> supportingIds = List.of("EVD-GIT-1234567", "EVD-CFG-1234567");
        List<String> contradictoryIds = List.of("EVD-CONTRA-1234567");

        InvestigationEntity inv = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "test-service", "prod",
                "/repos/test", "COMPLETED", "Summary", "CONFIRMED", 0.95, reportId
        );
        investigationRepository.save(inv);

        InvestigationAgentReport domainReport = new InvestigationAgentReport(
                reportId,
                invId,
                "Executive Summary",
                rootCause,
                Collections.emptyList(),
                supportingIds,
                contradictoryIds,
                null,
                "Reasoning Narrative",
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("Step 1"),
                List.of("Uncertainty 1"),
                AgentInvestigationStatus.CONFIRMED,
                Collections.emptyList(),
                Collections.emptyList(),
                Instant.now()
        );

        AgentReportEntity entity = persistenceMapper.toEntity(domainReport);
        agentReportRepository.save(entity);

        // Fetch back directly from PostgreSQL
        Optional<AgentReportEntity> loadedOpt = agentReportRepository.findById(reportId);
        assertThat(loadedOpt).isPresent();

        AgentReportEntity loaded = loadedOpt.get();
        assertThat(loaded.getPrimarySuspectedRootCause()).isEqualTo(rootCause);
        assertThat(loaded.getSupportingEvidenceIds()).contains("EVD-GIT-1234567");
        assertThat(loaded.getContradictoryEvidenceIds()).contains("EVD-CONTRA-1234567");

        // Convert back to domain
        InvestigationAgentReport rehydrated = persistenceMapper.toDomain(loaded, Collections.emptyList());
        assertThat(rehydrated.reportId()).isEqualTo(reportId);
        assertThat(rehydrated.primarySuspectedRootCause()).isEqualTo(rootCause);
        assertThat(rehydrated.supportingEvidenceIds()).containsExactlyElementsOf(supportingIds);
        assertThat(rehydrated.contradictoryEvidenceIds()).containsExactlyElementsOf(contradictoryIds);
    }

    @Test
    @DisplayName("Investigation rehydration should accurately reconstruct commitsAnalyzed and errorsDetected from PostgreSQL records")
    @Transactional
    void shouldReconstructCommitAndErrorCountAccurately() {
        UUID invId = UUID.randomUUID();

        InvestigationEntity inv = new InvestigationEntity(
                invId, InvestigationMode.WORKING_TREE_FORENSICS, "TacitIQ-Forensics", "Local",
                "/repos/TacitIQ", "COMPLETED", "Forensic analysis", "CONFIRMED", 0.95, "REP-TEST"
        );
        investigationRepository.save(inv);

        // Save 5 Git commit evidence items
        for (int i = 1; i <= 5; i++) {
            String sha = "commit" + i + "000000000000000000000000000";
            EvidenceItem item = EvidenceItem.create(
                    "EVD-GIT-" + sha.substring(0, 7),
                    EvidenceType.COMMIT,
                    "GIT",
                    sha,
                    Instant.now(),
                    "Commit message " + i,
                    Map.of("author", "dev", "investigationId", invId.toString())
            );
            evidenceRepository.save(persistenceMapper.toEntity(item, invId));
        }

        // Save 10 working-tree items (must NEVER be counted as runtime errors)
        for (int i = 1; i <= 10; i++) {
            EvidenceItem item = EvidenceItem.create(
                    "EVD-WT-MISSING-" + i,
                    EvidenceType.WORKING_TREE_CHANGE,
                    "WORKING_TREE",
                    "file" + i + ".java",
                    Instant.now(),
                    "Missing file " + i,
                    Map.of("changeType", "MISSING", "investigationId", invId.toString())
            );
            evidenceRepository.save(persistenceMapper.toEntity(item, invId));
        }

        // Rehydrate domain result via service
        Optional<InvestigationResult> resultOpt = investigationService.getInvestigation(invId);
        assertThat(resultOpt).isPresent();

        InvestigationResult result = resultOpt.get();
        assertThat(result.commitsAnalyzed()).isEqualTo(5);
        assertThat(result.errorsDetected()).isEqualTo(0);
        assertThat(result.primaryException()).isNull();
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.relationships()).isEmpty();
    }

    @Test
    @DisplayName("Timeline CONFIG_DELTA evidence references must resolve to registered evidence items")
    @Transactional
    void shouldResolveTimelineConfigEvidenceItems() {
        UUID invId = UUID.randomUUID();
        String commitSha = "7432e51928374650192837465019283746501928";
        String canonicalConfigId = ConcreteEvidenceRegistry.configEvidenceId(commitSha);

        InvestigationEntity inv = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "config-service", "prod",
                "/repos/config", "COMPLETED", "Config test", "CONFIRMED", 0.90, "REP-CFG"
        );
        investigationRepository.save(inv);

        // Register CONFIG_DIFF evidence item
        EvidenceItem cfgItem = EvidenceItem.create(
                canonicalConfigId,
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.yml",
                Instant.now(),
                "Config delta in application.yml",
                Map.of("investigationId", invId.toString())
        );
        evidenceRepository.save(persistenceMapper.toEntity(cfgItem, invId));

        // Save CONFIG_DELTA timeline event referencing the canonical evidence ID
        TimelineEvent te = new TimelineEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                "CONFIG_DELTA",
                "GIT",
                "7432e51:application.yml",
                "Config delta in application.yml",
                "Modified configuration keys",
                false,
                canonicalConfigId,
                Map.of("filePath", "application.yml")
        );
        timelineRepository.save(persistenceMapper.toEntity(te, invId));

        // Verify resolution
        List<TimelineEvent> timeline = investigationService.getTimeline(invId);
        assertThat(timeline).isNotEmpty();

        TimelineEvent loadedEvent = timeline.get(0);
        assertThat(loadedEvent.evidenceId()).isEqualTo(canonicalConfigId);

        Optional<EvidenceItemEntity> resolvedEvidence = evidenceRepository.findByEvidenceId(loadedEvent.evidenceId());
        assertThat(resolvedEvidence).isPresent();
        assertThat(resolvedEvidence.get().getEvidenceType()).isEqualTo("CONFIG_DIFF");
    }

    @Test
    @DisplayName("Original TacitIQ EconomicTimes investigation (5c48eb24) remains intact and readable in PostgreSQL")
    void shouldVerifyExistingTacitIqInvestigationRemainsIntact() {
        UUID tacitiqId = ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID;

        Optional<InvestigationEntity> invOpt = investigationRepository.findById(tacitiqId);
        assertThat(invOpt).as("Existing TacitIQ investigation must be present in PostgreSQL").isPresent();

        InvestigationEntity inv = invOpt.get();
        assertThat(inv.getServiceName()).isEqualTo("TacitIQ-EconomicTimes");
        assertThat(inv.getEnvironment()).isEqualTo("Local Development");
        assertThat(inv.getStatus()).isEqualTo("COMPLETED");
        assertThat(inv.getVerdict()).isEqualTo("CONFIRMED");

        // Verify evidence count
        long evidenceCount = evidenceRepository.countByInvestigationId(tacitiqId);
        assertThat(evidenceCount).isGreaterThanOrEqualTo(103L);

        // Verify agent report
        Optional<AgentReportEntity> reportOpt = agentReportRepository.findById(ActiveInvestigationBootstrapService.TARGET_REPORT_ID);
        assertThat(reportOpt).isPresent();
        AgentReportEntity report = reportOpt.get();
        assertThat(report.getVerdict()).isEqualTo("CONFIRMED");
        assertThat(report.getPrimarySuspectedRootCause()).isNotNull();
        assertThat(report.getPrimarySuspectedRootCause()).contains("Working tree filesystem anomaly");
    }

    @Test
    @DisplayName("Investigation with Hypotheses and STACK_FRAME evidence survives PostgreSQL cold rehydration")
    void shouldPersistAndRehydrateHypothesesAndStackFrameFromPostgreSql() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "checkout-service", "staging",
                "/repos/checkout", "COMPLETED", "Hikari CP failure", "PROBABLE", 0.85, "REP-CHK-01"
        );
        investigationRepository.save(entity);

        // Add STACK_FRAME evidence
        EvidenceItemEntity frameEntity = new EvidenceItemEntity(
                UUID.randomUUID(), "EVD-FRAME-checkout-Order.java", invId, "STACK_FRAME",
                "STACK_TRACE", "Order.java:55", "Stack frame Order.java matches commit",
                "{}", "hash-frame"
        );
        evidenceRepository.save(frameEntity);

        // Add Hypothesis
        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                25.0, 20.0, 15.0, 0.0, List.of("Score reason"), Collections.emptyList()
        );
        InvestigationHypothesisEntity hypEntity = new InvestigationHypothesisEntity(
                UUID.randomUUID(), UUID.randomUUID(), invId, 1, breakdown.normalizedScore(),
                "CONFIRMED", "Commit c0ffee1 modified Hikari pool", "Check pool size",
                "None", "c0ffee1", "[\"EVD-FRAME-checkout-Order.java\"]", "[]",
                "[\"RULE_CONFIG_DIFF\"]", "{}", "[]", "Top candidate",
                "{\"temporalPoints\":25.0,\"overlapPoints\":20.0,\"agreementPoints\":15.0,\"contradictionPenalty\":0.0,\"rawScore\":60.0,\"normalizedScore\":0.71}"
        );
        hypothesisRepository.save(hypEntity);

        // Simulate cold rehydration via InvestigationService
        Optional<InvestigationResult> resultOpt = investigationService.getInvestigation(invId);
        assertThat(resultOpt).isPresent();
        InvestigationResult result = resultOpt.get();

        assertThat(result.errorsDetected()).isEqualTo(1);
        assertThat(result.hypotheses()).hasSize(1);
        Hypothesis rehydratedHypo = result.hypotheses().getFirst();
        assertThat(rehydratedHypo.getRank()).isEqualTo(1);
        assertThat(rehydratedHypo.getSummary()).isEqualTo("Commit c0ffee1 modified Hikari pool");
        assertThat(rehydratedHypo.getSupportingEvidenceIds()).containsExactly("EVD-FRAME-checkout-Order.java");
        assertThat(result.evidenceByCommitSha()).containsKey("c0ffee1");
    }
}
