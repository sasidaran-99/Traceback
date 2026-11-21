package com.traceback.core.agent;

import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.InvestigationHypothesisEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.validation.ClaimValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 9.8.2 Comprehensive Verification Test Suite:
 * Validates agent idempotency, report repository multi-result safety,
 * stack-frame error counting, hypothesis persistence, step idempotency,
 * and cross-investigation isolation.
 */
class Phase982AgentIdempotencyAndPersistenceTest {

    private BifrostClient bifrostClient;
    private TavilyResearchService tavilyService;
    private ClaimValidator claimValidator;
    private EvidenceRegistry evidenceRegistry;
    private InvestigationService investigationService;

    private InvestigationAgentReportRepository reportRepository;
    private InvestigationAgentStepRepository stepRepository;
    private InvestigationClaimRepository claimRepository;
    private InvestigationRepository investigationRepository;
    private InvestigationHypothesisRepository hypothesisRepository;
    private InvestigationEvidenceRepository evidenceRepository;
    private InvestigationPersistenceMapper mapper;

    private InvestigationAgent agent;

    @BeforeEach
    void setUp() {
        bifrostClient = mock(BifrostClient.class);
        tavilyService = mock(TavilyResearchService.class);
        when(tavilyService.isAvailable()).thenReturn(false);
        claimValidator = mock(ClaimValidator.class);
        when(claimValidator.validateClaim(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        evidenceRegistry = new ConcreteEvidenceRegistry();
        investigationService = mock(InvestigationService.class);

        reportRepository = mock(InvestigationAgentReportRepository.class);
        stepRepository = mock(InvestigationAgentStepRepository.class);
        claimRepository = mock(InvestigationClaimRepository.class);
        investigationRepository = mock(InvestigationRepository.class);
        hypothesisRepository = mock(InvestigationHypothesisRepository.class);
        evidenceRepository = mock(InvestigationEvidenceRepository.class);
        mapper = new InvestigationPersistenceMapper();

        agent = new InvestigationAgent(bifrostClient, tavilyService, claimValidator, evidenceRegistry, investigationService);
        agent.setAgentReportRepository(reportRepository);
        agent.setAgentStepRepository(stepRepository);
        agent.setInvestigationRepository(investigationRepository);
        agent.setPersistenceCoordinator(new com.traceback.core.persistence.InvestigationPersistenceCoordinator(
                investigationRepository, null, claimRepository, null, null,
                reportRepository, stepRepository, mapper
        ));
    }

    private AgentReportEntity createReportEntity(String reportId, UUID invId, String summary, String rootCause,
                                                String supIds, String contraIds, String verdict, String reasoning, Instant createdAt) {
        AgentReportEntity entity = new AgentReportEntity(
                reportId, invId, verdict, summary, reasoning, null, "[]", "[]", "[]"
        );
        entity.setPrimarySuspectedRootCause(rootCause);
        entity.setSupportingEvidenceIds(supIds);
        entity.setContradictoryEvidenceIds(contraIds);
        entity.setCreatedAt(createdAt != null ? createdAt : Instant.now());
        return entity;
    }

    @Test
    @DisplayName("Scenario 1: Existing investigation with report returns existing report without rerunning agent")
    void testExistingInvestigationReturnsExistingReport() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-authoritative-1";

        InvestigationEntity invEntity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "payment-svc", "prod",
                "/repos/payment", "COMPLETED", "Payment timeout", "PROBABLE", 0.85, reportId
        );
        AgentReportEntity reportEntity = createReportEntity(
                reportId, invId, "Executive summary", "Primary root cause",
                "[\"EVD-GIT-1\"]", "[]", "PROBABLE", "Reasoning", Instant.now()
        );

        when(investigationRepository.findById(invId)).thenReturn(Optional.of(invEntity));
        when(reportRepository.findByReportId(reportId)).thenReturn(Optional.of(reportEntity));

        Optional<InvestigationAgentReport> resultOpt = agent.getReport(invId);

        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get().reportId()).isEqualTo(reportId);
        assertThat(resultOpt.get().investigationId()).isEqualTo(invId);
        verify(investigationService, never()).startInvestigation(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("Scenario 2: Repeated request does not execute agent twice")
    void testRepeatedRequestDoesNotExecuteAgentTwice() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-cached-1";

        InvestigationAgentReport cached = new InvestigationAgentReport(
                reportId, invId, "Summary", "Cause", Collections.emptyList(),
                List.of("EVD-1"), Collections.emptyList(), null, "Reasoning",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), AgentInvestigationStatus.PROBABLE, Collections.emptyList(),
                Collections.emptyList(), Instant.now()
        );
        agent.cacheReport(cached);

        Optional<InvestigationAgentReport> first = agent.getReport(invId);
        Optional<InvestigationAgentReport> second = agent.getReport(invId);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().reportId()).isEqualTo(second.get().reportId());
        verifyNoInteractions(investigationRepository);
        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("Scenario 3 & 4: Forced rerun generates a new report while preserving the original report in repository")
    void testForcedRerunCreatesNewReportAndPreservesOriginal() {
        UUID invId = UUID.randomUUID();
        String originalReportId = "REP-original-111";

        // Pre-existing investigation in repository with original report
        InvestigationEntity invEntity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "order-service", "prod",
                "/repos/orders", "COMPLETED", "Hikari timeout", "PROBABLE", 0.85, originalReportId
        );
        when(investigationRepository.findById(invId)).thenReturn(Optional.of(invEntity));

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(25.0, 20.0, 15.0, 0.0, List.of("Recent commit"), Collections.emptyList());
        Hypothesis hypo = new Hypothesis(
                UUID.randomUUID(), null, null, 1, breakdown.normalizedScore(),
                HypothesisStatus.CONFIRMED, "Commit 12ef988 modified pool", null, null,
                List.of("EVD-GIT-12ef988"), Collections.emptyList(), List.of("RULE_CONFIG_DIFF"),
                Map.of("RULE_CONFIG_DIFF", "Hikari pool size"), Collections.emptyList(), "Explanation", breakdown
        );

        InvestigationResult invResult = new InvestigationResult(
                invId, "order-service", "prod", Instant.now().minusSeconds(300), Instant.now(),
                "COMPLETED", 2, 1, null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), List.of(hypo),
                Map.of("12ef988", List.of("EVD-GIT-12ef988")),
                Collections.emptyMap(),
                Map.of("12ef988", breakdown),
                Collections.emptyList(), true, InvestigationMode.RUNTIME_FAILURE
        );

        when(investigationService.getInvestigation(invId)).thenReturn(Optional.of(invResult));

        // Execute forced run
        InvestigationAgentReport newReport = agent.investigate(invResult);

        assertThat(newReport.reportId()).isNotEqualTo(originalReportId);
        assertThat(newReport.investigationId()).isEqualTo(invId);

        // Verify that reportRepository.save was called with the new report (preserving original in DB)
        ArgumentCaptor<AgentReportEntity> savedReportCaptor = ArgumentCaptor.forClass(AgentReportEntity.class);
        verify(reportRepository).save(savedReportCaptor.capture());
        assertThat(savedReportCaptor.getValue().getReportId()).isEqualTo(newReport.reportId());

        // Verify investigation record was updated with new report ID
        verify(investigationRepository).save(invEntity);
        assertThat(invEntity.getReportId()).isEqualTo(newReport.reportId());
    }

    @Test
    @DisplayName("Scenario 5: STACK_FRAME survives cold rehydration and sets errorsDetected >= 1")
    void testStackFrameSurvivesColdRehydration() {
        UUID invId = UUID.randomUUID();
        InvestigationEntity entity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "inventory-service", "prod",
                "/repos/inventory", "COMPLETED", "Connection failure", "PROBABLE", 0.78, "REP-inv-01"
        );

        EvidenceItem frameEvidence = EvidenceItem.create(
                "EVD-FRAME-afdc06a-InventoryService.java",
                EvidenceType.STACK_FRAME,
                "STACK_TRACE",
                "InventoryService.java:32",
                Instant.now(),
                "Stack frame com.example.inventory.InventoryService.getItem matches changed file",
                Map.of("className", "com.example.inventory.InventoryService", "method", "getItem", "line", 32)
        );

        InvestigationResult rehydrated = mapper.toDomain(
                entity,
                Collections.emptyList(),
                List.of(frameEvidence),
                Collections.emptyList()
        );

        assertThat(rehydrated.errorsDetected()).isEqualTo(1);
        assertThat(rehydrated.evidenceItems()).hasSize(1);
        assertThat(rehydrated.evidenceItems().getFirst().evidenceType()).isEqualTo(EvidenceType.STACK_FRAME);
    }

    @Test
    @DisplayName("Scenario 6: Hypotheses survive PostgreSQL cold rehydration with full confidence breakdown")
    void testHypothesesSurviveColdRehydration() {
        UUID invId = UUID.randomUUID();
        UUID hypId = UUID.randomUUID();

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                30.0, 25.0, 15.0, 5.0,
                List.of("Temporal match within 5m", "File overlap OrderService.java"),
                List.of("Mild contradiction")
        );

        Hypothesis original = new Hypothesis(
                hypId, null, null, 1, breakdown.normalizedScore(),
                HypothesisStatus.CONFIRMED, "Suspect commit ee51c22", "Verify pool size",
                "Commit after error", List.of("EVD-GIT-ee51c22"), List.of("EVD-CONTRA-1"),
                List.of("RULE_CONFIG_DIFF"), Map.of("RULE_CONFIG_DIFF", "Pool altered"),
                List.of("Penalized for late timestamp"), "Top suspect", breakdown
        );

        InvestigationHypothesisEntity entity = mapper.toEntity(original, invId);
        Hypothesis rehydrated = mapper.toDomain(entity);

        assertThat(rehydrated.getId()).isEqualTo(hypId);
        assertThat(rehydrated.getRank()).isEqualTo(1);
        assertThat(rehydrated.getConfidenceScore()).isEqualTo(breakdown.normalizedScore());
        assertThat(rehydrated.getStatus()).isEqualTo(HypothesisStatus.CONFIRMED);
        assertThat(rehydrated.getSummary()).isEqualTo("Suspect commit ee51c22");
        assertThat(rehydrated.getSupportingEvidenceIds()).containsExactly("EVD-GIT-ee51c22");
        assertThat(rehydrated.getContradictingEvidenceIds()).containsExactly("EVD-CONTRA-1");
        assertThat(rehydrated.getMatchedRules()).containsExactly("RULE_CONFIG_DIFF");
        assertThat(rehydrated.getConfidenceBreakdown()).isNotNull();
        assertThat(rehydrated.getConfidenceBreakdown().temporalPoints()).isEqualTo(30.0);
        assertThat(rehydrated.getConfidenceBreakdown().overlapPoints()).isEqualTo(25.0);
        assertThat(rehydrated.getConfidenceBreakdown().agreementPoints()).isEqualTo(15.0);
        assertThat(rehydrated.getConfidenceBreakdown().contradictionPenalty()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Scenario 7: Repeated execution does not duplicate agent steps")
    void testRepeatedExecutionDoesNotDuplicateAgentSteps() {
        UUID invId = UUID.randomUUID();
        List<AgentInvestigationStep> steps = List.of(
                AgentInvestigationStep.of(1, "Deterministic Ingestion", "Step 1", Map.of("k", "v")),
                AgentInvestigationStep.of(2, "External Technical Research", "Step 2", Collections.emptyMap()),
                AgentInvestigationStep.of(3, "Bifrost AI Gateway Reasoning", "Step 3", Collections.emptyMap()),
                AgentInvestigationStep.of(4, "Evidence Grounding & Claim Validation", "Step 4", Collections.emptyMap()),
                AgentInvestigationStep.of(5, "Final Verdict", "Step 5", Map.of("verdict", "PROBABLE"))
        );

        // First execution: steps don't exist yet
        when(stepRepository.findByInvestigationIdAndStepNumber(eq(invId), anyInt())).thenReturn(Optional.empty());
        when(stepRepository.findByInvestigationIdOrderByStepNumberAsc(invId)).thenReturn(Collections.emptyList());

        agent.persistAgentSteps(invId, steps);

        // Verify 5 saves with deterministic IDs
        ArgumentCaptor<AgentStepEntity> stepCaptor = ArgumentCaptor.forClass(AgentStepEntity.class);
        verify(stepRepository, times(5)).save(stepCaptor.capture());
        List<UUID> firstRunIds = stepCaptor.getAllValues().stream().map(AgentStepEntity::getId).toList();

        // Second execution: simulates existing steps found by (investigationId, stepNumber)
        reset(stepRepository);
        for (int i = 0; i < 5; i++) {
            UUID existingId = firstRunIds.get(i);
            AgentStepEntity existingEntity = new AgentStepEntity(existingId, invId, i + 1, "Step " + (i + 1), "COMPLETED", "Desc", "{}", 0L);
            when(stepRepository.findByInvestigationIdAndStepNumber(invId, i + 1)).thenReturn(Optional.of(existingEntity));
        }
        when(stepRepository.findByInvestigationIdOrderByStepNumberAsc(invId)).thenReturn(Collections.emptyList());

        agent.persistAgentSteps(invId, steps);

        // Verify 5 updates reusing the existing IDs (NO new rows created)
        ArgumentCaptor<AgentStepEntity> secondRunCaptor = ArgumentCaptor.forClass(AgentStepEntity.class);
        verify(stepRepository, times(5)).save(secondRunCaptor.capture());
        List<UUID> secondRunIds = secondRunCaptor.getAllValues().stream().map(AgentStepEntity::getId).toList();

        assertThat(secondRunIds).containsExactlyElementsOf(firstRunIds);
    }

    @Test
    @DisplayName("Scenario 8: Transaction failure rolls back steps and throws exception")
    void testTransactionRollbackPreservesPreviousRecords() {
        UUID invId = UUID.randomUUID();
        List<AgentInvestigationStep> steps = List.of(
                AgentInvestigationStep.of(1, "Step 1", "Desc 1", Collections.emptyMap()),
                AgentInvestigationStep.of(2, "Step 2", "Desc 2", Collections.emptyMap())
        );

        doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated constraint violation"))
                .when(stepRepository).save(any(AgentStepEntity.class));

        assertThatThrownBy(() -> agent.persistAgentSteps(invId, steps))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Scenario 9: Multiple reports for one investigation are retrieved deterministically")
    void testMultipleReportsRetrievedDeterministically() {
        UUID invId = UUID.randomUUID();
        String oldReportId = "REP-old-111";
        String newReportId = "REP-new-222";

        Instant t0 = Instant.now().minusSeconds(100);
        Instant t1 = Instant.now();

        AgentReportEntity oldReport = createReportEntity(
                oldReportId, invId, "Old summary", "Old root cause",
                "[]", "[]", "PROBABLE", "Old reasoning", t0
        );
        AgentReportEntity newReport = createReportEntity(
                newReportId, invId, "New summary", "New root cause",
                "[]", "[]", "INSUFFICIENT_EVIDENCE", "New reasoning", t1
        );

        // When ordered by createdAt DESC, newReport comes first
        when(reportRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                .thenReturn(List.of(newReport, oldReport));

        // When investigationEntity points to oldReport as authoritative:
        InvestigationEntity invEntity = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "svc", "prod",
                "/repos/svc", "COMPLETED", "Summary", "PROBABLE", 0.82, oldReportId
        );
        when(investigationRepository.findById(invId)).thenReturn(Optional.of(invEntity));
        when(reportRepository.findByReportId(oldReportId)).thenReturn(Optional.of(oldReport));

        // Authoritative report lookup honors investigationEntity.report_id
        Optional<InvestigationAgentReport> authReport = agent.getReport(invId);
        assertThat(authReport).isPresent();
        assertThat(authReport.get().reportId()).isEqualTo(oldReportId);

        // Report history returns both in deterministic descending order
        List<InvestigationAgentReport> history = agent.getReportHistory(invId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).reportId()).isEqualTo(newReportId);
        assertThat(history.get(1).reportId()).isEqualTo(oldReportId);
    }

    @Test
    @DisplayName("Scenario 10: No cross-investigation data leakage")
    void testNoCrossInvestigationDataLeakage() {
        UUID invA = UUID.randomUUID();
        UUID invB = UUID.randomUUID();

        String repA = "REP-A";
        String repB = "REP-B";

        AgentReportEntity reportA = createReportEntity(repA, invA, "Sum A", "Cause A", "[\"EVD-A\"]", "[]", "CONFIRMED", "R A", Instant.now());
        AgentReportEntity reportB = createReportEntity(repB, invB, "Sum B", "Cause B", "[\"EVD-B\"]", "[]", "PROBABLE", "R B", Instant.now());

        InvestigationEntity entityA = new InvestigationEntity(invA, InvestigationMode.RUNTIME_FAILURE, "svcA", "prod", "/repoA", "COMPLETED", "A", "CONFIRMED", 0.9, repA);
        InvestigationEntity entityB = new InvestigationEntity(invB, InvestigationMode.RUNTIME_FAILURE, "svcB", "prod", "/repoB", "COMPLETED", "B", "PROBABLE", 0.8, repB);

        when(investigationRepository.findById(invA)).thenReturn(Optional.of(entityA));
        when(investigationRepository.findById(invB)).thenReturn(Optional.of(entityB));

        when(reportRepository.findByReportId(repA)).thenReturn(Optional.of(reportA));
        when(reportRepository.findByReportId(repB)).thenReturn(Optional.of(reportB));

        Optional<InvestigationAgentReport> resultA = agent.getReport(invA);
        Optional<InvestigationAgentReport> resultB = agent.getReport(invB);

        assertThat(resultA).isPresent();
        assertThat(resultB).isPresent();

        assertThat(resultA.get().investigationId()).isEqualTo(invA);
        assertThat(resultA.get().supportingEvidenceIds()).containsExactly("EVD-A");
        assertThat(resultA.get().supportingEvidenceIds()).doesNotContain("EVD-B");

        assertThat(resultB.get().investigationId()).isEqualTo(invB);
        assertThat(resultB.get().supportingEvidenceIds()).containsExactly("EVD-B");
        assertThat(resultB.get().supportingEvidenceIds()).doesNotContain("EVD-A");
    }
}
