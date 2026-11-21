package com.traceback.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationPersistenceMapperTest {

    private InvestigationPersistenceMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mapper = new InvestigationPersistenceMapper(objectMapper);
    }

    @Test
    @DisplayName("Should reconstruct commitsAnalyzed from persisted Git commit evidence")
    void shouldReconstructCommitCountFromEvidence() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.WORKING_TREE_FORENSICS, "TacitIQ", "Local",
                "/repos/TacitIQ", "COMPLETED", "Summary", "CONFIRMED", 0.95, "REP-01"
        );

        List<EvidenceItem> evidence = new ArrayList<>();
        // 15 distinct git commits
        for (int i = 1; i <= 15; i++) {
            String sha = String.format("sha%05d", i);
            evidence.add(EvidenceItem.create(
                    "EVD-GIT-" + sha.substring(0, 7),
                    EvidenceType.COMMIT,
                    "GIT",
                    sha,
                    Instant.now(),
                    "Commit " + i,
                    Map.of("author", "dev")
            ));
        }

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), evidence, Collections.emptyList());

        assertThat(result.commitsAnalyzed()).isEqualTo(15);
        assertThat(result.errorsDetected()).isEqualTo(0);
        assertThat(result.primaryException()).isNull();
    }

    @Test
    @DisplayName("Should reconstruct errorsDetected from runtime error evidence and NEVER count working-tree anomalies")
    void shouldReconstructErrorCountWithoutCountingWorkingTreeAnomalies() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.WORKING_TREE_FORENSICS, "order-service", "prod",
                "/repos/order", "COMPLETED", "Summary", "CONFIRMED", 0.95, "REP-02"
        );

        List<EvidenceItem> evidence = new ArrayList<>();
        // 80 working tree change items
        for (int i = 1; i <= 80; i++) {
            evidence.add(EvidenceItem.create(
                    "EVD-WT-MISSING-" + i,
                    EvidenceType.WORKING_TREE_CHANGE,
                    "WORKING_TREE",
                    "file" + i + ".java",
                    Instant.now(),
                    "Missing file " + i,
                    Map.of("changeType", "MISSING")
            ));
        }

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), evidence, Collections.emptyList());

        // Working tree anomalies MUST NOT be counted as runtime errors
        assertThat(result.errorsDetected()).isEqualTo(0);
        assertThat(result.commitsAnalyzed()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reconstruct runtime errorsDetected from LOG_ERROR and ERROR_SIGNATURE evidence")
    void shouldReconstructRuntimeErrorCountFromLogs() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "payment-service", "prod",
                "/repos/payment", "COMPLETED", "Summary", "CONFIRMED", 0.85, "REP-03"
        );

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(EvidenceItem.create(
                "EVD-LOG-SIG",
                EvidenceType.ERROR_SIGNATURE,
                "LOG",
                "java.sql.SQLTimeoutException",
                Instant.now(),
                "Primary error signature: java.sql.SQLTimeoutException",
                Map.of("signature", "HikariTimeout", "count", 4)
        ));
        evidence.add(EvidenceItem.create(
                "EVD-LOG-1",
                EvidenceType.LOG_ERROR,
                "LOG",
                "line 45",
                Instant.now(),
                "Timeout acquiring connection",
                Map.of("line", 45)
        ));

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), evidence, Collections.emptyList());

        assertThat(result.errorsDetected()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should recover primaryException from STACK_TRACE evidence metadata when available")
    void shouldRecoverPrimaryExceptionFromStackTraceEvidence() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "payment-service", "prod",
                "/repos/payment", "COMPLETED", "Summary", "CONFIRMED", 0.85, "REP-04"
        );

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(EvidenceItem.create(
                "EVD-STACK-ROOT",
                EvidenceType.STACK_TRACE,
                "STACK_TRACE",
                "java.sql.SQLTimeoutException",
                Instant.now(),
                "Root exception: java.sql.SQLTimeoutException: Connection pool timeout",
                Map.of("type", "java.sql.SQLTimeoutException")
        ));

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), evidence, Collections.emptyList());

        assertThat(result.primaryException()).isNotNull();
        assertThat(result.primaryException().exceptionType()).isEqualTo("java.sql.SQLTimeoutException");
        assertThat(result.primaryException().message()).contains("Connection pool timeout");
        assertThat(result.errorsDetected()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should explicitly leave hypotheses and relationships empty per audit rules rather than inventing synthetic scores")
    void shouldReportLimitationRatherThanInventingSyntheticHypotheses() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.WORKING_TREE_FORENSICS, "TacitIQ", "Local",
                "/repos/TacitIQ", "COMPLETED", "Summary", "CONFIRMED", 0.95, "REP-05"
        );

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        // Must NOT invent synthetic hypotheses with fake confidence scores
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.relationships()).isEmpty();
    }

    @Test
    @DisplayName("Should serialize and deserialize Agent Report with primarySuspectedRootCause, supportingEvidenceIds, and contradictoryEvidenceIds")
    void shouldPersistAndRehydrateAgentReportEvidenceCollections() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);
        String rootCause = "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.";
        List<String> supporting = List.of("EVD-WT-MISSING-01", "EVD-WT-MISSING-02", "EVD-WT-MISSING-03");
        List<String> contradictory = List.of("EVD-MILD-CONTRA");

        InvestigationAgentReport domainReport = new InvestigationAgentReport(
                reportId,
                invId,
                "Working tree anomaly detected",
                rootCause,
                Collections.emptyList(),
                supporting,
                contradictory,
                null,
                "Forensic reasoning narrative",
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("Step 1", "Step 2"),
                List.of("Uncertainty 1"),
                AgentInvestigationStatus.CONFIRMED,
                Collections.emptyList(),
                Collections.emptyList(),
                Instant.now()
        );

        // Map to JPA entity
        AgentReportEntity entity = mapper.toEntity(domainReport);

        assertThat(entity.getReportId()).isEqualTo(reportId);
        assertThat(entity.getInvestigationId()).isEqualTo(invId);
        assertThat(entity.getPrimarySuspectedRootCause()).isEqualTo(rootCause);
        assertThat(entity.getSupportingEvidenceIds()).contains("EVD-WT-MISSING-01");
        assertThat(entity.getContradictoryEvidenceIds()).contains("EVD-MILD-CONTRA");

        // Map back to domain record
        InvestigationAgentReport rehydrated = mapper.toDomain(entity, Collections.emptyList());

        assertThat(rehydrated.reportId()).isEqualTo(reportId);
        assertThat(rehydrated.investigationId()).isEqualTo(invId);
        assertThat(rehydrated.primarySuspectedRootCause()).isEqualTo(rootCause);
        assertThat(rehydrated.supportingEvidenceIds()).containsExactlyElementsOf(supporting);
        assertThat(rehydrated.contradictoryEvidenceIds()).containsExactlyElementsOf(contradictory);
        assertThat(rehydrated.finalStatus()).isEqualTo(AgentInvestigationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("STACK_FRAME evidence should be recognized as runtime error evidence and set errorsDetected >= 1")
    void shouldReconstructRuntimeErrorCountFromStackFrameEvidence() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "order-service", "prod",
                "/repos/orders", "COMPLETED", "Order service failure", "PROBABLE", 0.82, "REP-16c0831f"
        );

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(EvidenceItem.create(
                "EVD-FRAME-12ef988-OrderService.java",
                EvidenceType.STACK_FRAME,
                "STACK_TRACE",
                "OrderService.java:45",
                Instant.now(),
                "Stack frame com.example.orders.OrderService.createOrder matches changed file",
                Map.of("className", "com.example.orders.OrderService", "method", "createOrder", "line", 45)
        ));

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), evidence, Collections.emptyList());

        assertThat(result.errorsDetected()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should serialize and deserialize Hypothesis with ConfidenceBreakdown and supporting evidence")
    void shouldPersistAndRehydrateInvestigationHypotheses() {
        UUID invId = UUID.randomUUID();
        UUID hypId = UUID.randomUUID();

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                25.0, 20.0, 15.0, 0.0,
                List.of("Recent commit within 15 mins", "Matched stack trace file OrderService.java"),
                Collections.emptyList()
        );

        com.traceback.core.domain.Hypothesis hypothesis = new com.traceback.core.domain.Hypothesis(
                hypId,
                null,
                null,
                1,
                breakdown.normalizedScore(),
                com.traceback.core.domain.HypothesisStatus.CONFIRMED,
                "Commit 12ef988 is the probable root cause",
                "Review Hikari configuration in OrderService",
                "No post-incident commits found",
                List.of("EVD-GIT-12ef988", "EVD-FRAME-12ef988-OrderService.java"),
                Collections.emptyList(),
                List.of("RULE_FILE_OVERLAP", "RULE_TEMPORAL_PROXIMITY"),
                Map.of("RULE_FILE_OVERLAP", "Changed OrderService.java"),
                Collections.emptyList(),
                "Top ranked candidate based on code overlap and temporal proximity",
                breakdown
        );

        com.traceback.core.domain.InvestigationHypothesisEntity entity = mapper.toEntity(hypothesis, invId);

        assertThat(entity.getHypothesisId()).isEqualTo(hypId);
        assertThat(entity.getInvestigationId()).isEqualTo(invId);
        assertThat(entity.getRank()).isEqualTo(1);
        assertThat(entity.getConfidenceScore()).isEqualTo(breakdown.normalizedScore());
        assertThat(entity.getSummary()).isEqualTo("Commit 12ef988 is the probable root cause");
        assertThat(entity.getSuspectedCommitSha()).isEqualTo("12ef988");
        assertThat(entity.getSupportingEvidenceIds()).contains("EVD-GIT-12ef988");

        com.traceback.core.domain.Hypothesis rehydrated = mapper.toDomain(entity);

        assertThat(rehydrated.getId()).isEqualTo(hypId);
        assertThat(rehydrated.getRank()).isEqualTo(1);
        assertThat(rehydrated.getConfidenceScore()).isEqualTo(breakdown.normalizedScore());
        assertThat(rehydrated.getStatus()).isEqualTo(com.traceback.core.domain.HypothesisStatus.CONFIRMED);
        assertThat(rehydrated.getSupportingEvidenceIds()).containsExactly("EVD-GIT-12ef988", "EVD-FRAME-12ef988-OrderService.java");
        assertThat(rehydrated.getConfidenceBreakdown()).isNotNull();
        assertThat(rehydrated.getConfidenceBreakdown().temporalPoints()).isEqualTo(25.0);
        assertThat(rehydrated.getConfidenceBreakdown().overlapPoints()).isEqualTo(20.0);
        assertThat(rehydrated.getConfidenceBreakdown().agreementPoints()).isEqualTo(15.0);
        assertThat(rehydrated.getConfidenceBreakdown().normalizedScore()).isEqualTo(breakdown.normalizedScore());
    }

    @Test
    @DisplayName("Rehydration with hypotheses should populate commit lookup maps in InvestigationResult")
    void shouldPopulateCommitMapsWhenRehydratingWithHypotheses() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "order-service", "prod",
                "/repos/orders", "COMPLETED", "Summary", "PROBABLE", 0.85, "REP-01"
        );

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                20.0, 20.0, 15.0, 0.0, List.of("Score reason"), Collections.emptyList()
        );
        com.traceback.core.domain.Hypothesis h = new com.traceback.core.domain.Hypothesis(
                UUID.randomUUID(), null, null, 1, breakdown.normalizedScore(),
                com.traceback.core.domain.HypothesisStatus.PROPOSED,
                "Commit ee51c22 modified Hikari pool", null, "Counter evidence",
                List.of("EVD-GIT-ee51c22", "EVD-CFG-ee51c22"), Collections.emptyList(),
                List.of("RULE_CONFIG_DIFF"), Map.of("RULE_CONFIG_DIFF", "Hikari max pool modified"),
                Collections.emptyList(), "Explanation", breakdown
        );

        InvestigationResult result = mapper.toDomain(entity, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), List.of(h));

        assertThat(result.hypotheses()).hasSize(1);
        assertThat(result.evidenceByCommitSha()).containsKey("ee51c22");
        assertThat(result.evidenceByCommitSha().get("ee51c22")).contains("EVD-GIT-ee51c22", "EVD-CFG-ee51c22");
        assertThat(result.counterEvidenceByCommitSha()).containsEntry("ee51c22", "Counter evidence");
        assertThat(result.confidenceBreakdownsByCommitSha()).containsKey("ee51c22");
    }

    @Test
    @DisplayName("Agent step persistence should generate deterministic UUIDs scoped to investigation and step number")
    void shouldGenerateDeterministicIdForAgentStep() {
        UUID invId = UUID.randomUUID();
        com.traceback.core.agent.AgentInvestigationStep step = com.traceback.core.agent.AgentInvestigationStep.of(
                1, "Deterministic Ingestion", "Ingested results", Map.of("commits", 2)
        );

        com.traceback.core.domain.AgentStepEntity entity1 = mapper.toEntity(step, invId);
        com.traceback.core.domain.AgentStepEntity entity2 = mapper.toEntity(step, invId);

        assertThat(entity1.getId()).isNotNull();
        assertThat(entity1.getId()).isEqualTo(entity2.getId());
        assertThat(entity1.getStepNumber()).isEqualTo(1);
        assertThat(entity1.getInvestigationId()).isEqualTo(invId);

        UUID existingId = UUID.randomUUID();
        com.traceback.core.domain.AgentStepEntity updated = mapper.toEntity(step, invId, existingId);
        assertThat(updated.getId()).isEqualTo(existingId);
    }
}
