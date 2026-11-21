package com.traceback.core;

import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.InvestigationHypothesisEntity;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCollector;
import com.traceback.core.git.workingtree.GitWorkingTreeCollector;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.persistence.InvestigationPersistenceCoordinator;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.ClaimValidator;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Duration;
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
 * Phase 2.49: Persistence Integrity Remediation Test Suite.
 *
 * Verifies remediation of:
 * - F-01: Coordinated multi-entity persistence in InvestigationAgent and InvestigationService.
 *         Ensures transactional boundary, atomic commit/rollback, and elimination of partial records.
 * - F-07: Explicit durable persistence status.
 *         Ensures database failures cannot be mistaken for durable success, in-memory fallbacks are
 *         distinct, and process/cache loss does not falsely return ephemeral results.
 */
@DisplayName("Phase 2.49: Persistence Integrity Remediation Tests")
public class Phase249PersistenceIntegrityTest {

    @Nested
    @DisplayName("F-01: Report Transaction and Coordinated Persistence Tests")
    class ReportTransactionTests {

        private InvestigationAgentReportRepository mockReportRepo;
        private InvestigationAgentStepRepository mockStepRepo;
        private InvestigationClaimRepository mockClaimRepo;
        private InvestigationRepository mockInvRepo;
        private InvestigationPersistenceMapper mapper;
        private InvestigationPersistenceCoordinator coordinator;

        private BifrostClient mockBifrostClient;
        private TavilyResearchService mockTavilyService;
        private ClaimValidator claimValidator;
        private EvidenceRegistry evidenceRegistry;
        private InvestigationService mockInvestigationService;
        private InvestigationAgent agent;

        @BeforeEach
        void setUp() {
            mockReportRepo = mock(InvestigationAgentReportRepository.class);
            mockStepRepo = mock(InvestigationAgentStepRepository.class);
            mockClaimRepo = mock(InvestigationClaimRepository.class);
            mockInvRepo = mock(InvestigationRepository.class);
            mapper = new InvestigationPersistenceMapper();

            coordinator = new InvestigationPersistenceCoordinator(
                    mockInvRepo, null, mockClaimRepo, null, null,
                    mockReportRepo, mockStepRepo, mapper
            );

            mockBifrostClient = mock(BifrostClient.class);
            when(mockBifrostClient.isAvailable()).thenReturn(false);
            mockTavilyService = mock(TavilyResearchService.class);
            when(mockTavilyService.isAvailable()).thenReturn(false);
            claimValidator = new DeterministicClaimValidator();
            evidenceRegistry = new ConcreteEvidenceRegistry();
            mockInvestigationService = mock(InvestigationService.class);

            agent = new InvestigationAgent(
                    mockBifrostClient, mockTavilyService, claimValidator,
                    evidenceRegistry, mockInvestigationService
            );
            agent.setPersistenceCoordinator(coordinator);
            agent.setAgentReportRepository(mockReportRepo);
            agent.setAgentStepRepository(mockStepRepo);
            agent.setClaimRepository(mockClaimRepo);
            agent.setInvestigationRepository(mockInvRepo);
        }

        @Test
        @DisplayName("Scenario 1: Report save succeeds and all related entities (steps, claims, investigation) succeed")
        void testReportSaveSucceeds_AllRelatedEntitiesPersisted() {
            UUID invId = UUID.randomUUID();
            InvestigationEntity existingInv = new InvestigationEntity(
                    invId, InvestigationMode.RUNTIME_FAILURE, "svc", "prod",
                    "/repo", "COMPLETED", "summary", null, 0.0, null
            );
            when(mockInvRepo.findById(invId)).thenReturn(Optional.of(existingInv));

            InvestigationClaim claim = InvestigationClaim.create(
                    "CLAIM-AGENT-PRIMARY-12345678", invId, "Root cause statement",
                    List.of("EVD-1"), Collections.emptyList()
            );
            AgentInvestigationStep step = AgentInvestigationStep.of(1, "Deterministic Ingestion", "Details", Map.of());

            InvestigationAgentReport report = new InvestigationAgentReport(
                    "REP-coord-1", invId, "Summary", "Root cause",
                    Collections.emptyList(), List.of("EVD-1"), Collections.emptyList(),
                    ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                    "AI Reasoning", Collections.emptyList(), Collections.emptyList(),
                    List.of("Verify commit"), List.of("Telemetry gaps"),
                    AgentInvestigationStatus.CONFIRMED, List.of(claim), List.of(step),
                    Instant.now()
            );

            boolean result = coordinator.persistReportCoordinated(report);

            assertThat(result).isTrue();
            verify(mockReportRepo).save(any(AgentReportEntity.class));
            verify(mockStepRepo).save(any(AgentStepEntity.class));
            verify(mockClaimRepo).save(any(ClaimEntity.class));
            verify(mockInvRepo).save(argThat(entity ->
                    "REP-coord-1".equals(entity.getReportId()) &&
                    "CONFIRMED".equals(entity.getVerdict()) &&
                    entity.getConfidenceScore() != null && entity.getConfidenceScore() > 0.8
            ));
        }

        @Test
        @DisplayName("Scenario 2: Agent-step save fails - coordinated transaction throws and rolls back")
        void testAgentStepSaveFails_ThrowsExceptionForRollback() {
            UUID invId = UUID.randomUUID();
            when(mockInvRepo.findById(invId)).thenReturn(Optional.of(new InvestigationEntity()));

            // Step repository fails with database integrity violation
            doThrow(new DataIntegrityViolationException("Database constraint violation on agent steps"))
                    .when(mockStepRepo).save(any(AgentStepEntity.class));

            InvestigationClaim claim = InvestigationClaim.create("CLAIM-1", invId, "stmt", List.of(), List.of());
            AgentInvestigationStep step = AgentInvestigationStep.of(1, "Step 1", "Desc", Map.of());

            InvestigationAgentReport report = new InvestigationAgentReport(
                    "REP-fail-step", invId, "Summ", "Cause",
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    null, "Reasoning", Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    AgentInvestigationStatus.PROBABLE, List.of(claim), List.of(step),
                    Instant.now()
            );

            // Coordinated method must NOT swallow exception; it must propagate to trigger @Transactional rollback
            assertThatThrownBy(() -> coordinator.persistReportCoordinated(report))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("constraint violation");

            // Claim and investigation updates must never have been reached
            verify(mockClaimRepo, never()).save(any(ClaimEntity.class));
            verify(mockInvRepo, never()).save(any(InvestigationEntity.class));
        }

        @Test
        @DisplayName("Scenario 3: Claim save fails - coordinated transaction throws and rolls back")
        void testClaimSaveFails_ThrowsExceptionForRollback() {
            UUID invId = UUID.randomUUID();
            when(mockInvRepo.findById(invId)).thenReturn(Optional.of(new InvestigationEntity()));

            // Claim repository fails
            doThrow(new DataIntegrityViolationException("Database deadlock on claims"))
                    .when(mockClaimRepo).save(any(ClaimEntity.class));

            InvestigationClaim claim = InvestigationClaim.create("CLAIM-1", invId, "stmt", List.of(), List.of());

            InvestigationAgentReport report = new InvestigationAgentReport(
                    "REP-fail-claim", invId, "Summ", "Cause",
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    null, "Reasoning", Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    AgentInvestigationStatus.PROBABLE, List.of(claim), Collections.emptyList(),
                    Instant.now()
            );

            assertThatThrownBy(() -> coordinator.persistReportCoordinated(report))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("deadlock");

            // Parent investigation entity must NOT be updated
            verify(mockInvRepo, never()).save(any(InvestigationEntity.class));
        }

        @Test
        @DisplayName("Scenario 4: Parent investigation update fails - throws to roll back entire report transaction")
        void testParentInvestigationUpdateFails_ThrowsForRollback() {
            UUID invId = UUID.randomUUID();
            when(mockInvRepo.findById(invId)).thenReturn(Optional.of(new InvestigationEntity()));
            doThrow(new RuntimeException("Optimistic locking failure on investigation"))
                    .when(mockInvRepo).save(any(InvestigationEntity.class));

            InvestigationAgentReport report = new InvestigationAgentReport(
                    "REP-fail-inv", invId, "Summ", "Cause",
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    null, "Reasoning", Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    AgentInvestigationStatus.CONFIRMED, Collections.emptyList(), Collections.emptyList(),
                    Instant.now()
            );

            assertThatThrownBy(() -> coordinator.persistReportCoordinated(report))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Optimistic locking failure");
        }

        @Test
        @DisplayName("Scenario 5: Agent execution handles persistence failure gracefully and returns clearly marked non-durable report")
        void testAgentExecution_WhenPersistenceFails_ReturnsNonDurableReport() {
            UUID invId = UUID.randomUUID();
            InvestigationResult invResult = new InvestigationResult(
                    invId, "payment-svc", "prod", Instant.now(), Instant.now(),
                    "COMPLETED", 5, 1, null, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyMap(), Collections.emptyMap()
            );

            // Force coordinator to fail
            doThrow(new DataIntegrityViolationException("Connection lost"))
                    .when(mockReportRepo).save(any(AgentReportEntity.class));

            InvestigationAgentReport generatedReport = agent.investigate(invResult);

            assertThat(generatedReport).isNotNull();
            assertThat(generatedReport.persistedDurable()).isFalse();
            assertThat(generatedReport.persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");

            // Verify in-memory store cached it with the explicit non-durable status
            Optional<InvestigationAgentReport> storedOpt = agent.getReport(invId);
            assertThat(storedOpt).isPresent();
            assertThat(storedOpt.get().persistedDurable()).isFalse();
            assertThat(storedOpt.get().persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");
        }

        @Test
        @DisplayName("Scenario 6: Transactional annotation is properly declared on coordinator methods")
        void testTransactionalAnnotationDeclared() throws NoSuchMethodException {
            Method reportMethod = InvestigationPersistenceCoordinator.class.getMethod(
                    "persistReportCoordinated", InvestigationAgentReport.class
            );
            Transactional reportTx = reportMethod.getAnnotation(Transactional.class);
            assertThat(reportTx)
                    .as("persistReportCoordinated must be @Transactional")
                    .isNotNull();
            assertThat(reportTx.rollbackFor())
                    .as("rollbackFor must include Exception.class")
                    .contains(Exception.class);

            Method invMethod = InvestigationPersistenceCoordinator.class.getMethod(
                    "persistInvestigationCoordinated", InvestigationResult.class, String.class
            );
            Transactional invTx = invMethod.getAnnotation(Transactional.class);
            assertThat(invTx)
                    .as("persistInvestigationCoordinated must be @Transactional")
                    .isNotNull();
            assertThat(invTx.rollbackFor())
                    .as("rollbackFor must include Exception.class")
                    .contains(Exception.class);
        }
    }

    @Nested
    @DisplayName("F-07: Durable Persistence Status and Rehydration Tests")
    class DurablePersistenceTests {

        private InvestigationRepository mockInvRepo;
        private InvestigationEvidenceRepository mockEvidenceRepo;
        private InvestigationClaimRepository mockClaimRepo;
        private InvestigationTimelineRepository mockTimelineRepo;
        private InvestigationHypothesisRepository mockHypothesisRepo;
        private InvestigationPersistenceMapper mapper;
        private InvestigationPersistenceCoordinator coordinator;

        private GitCollector mockGitCollector;
        private StackTraceParser mockStackTraceParser;
        private LogAnalyzer mockLogAnalyzer;
        private TimelineBuilder mockTimelineBuilder;
        private com.traceback.core.correlation.CorrelationEngine mockCorrelationEngine;
        private EvidenceRegistry evidenceRegistry;
        private InvestigationService service;

        @BeforeEach
        void setUp() {
            mockInvRepo = mock(InvestigationRepository.class);
            mockEvidenceRepo = mock(InvestigationEvidenceRepository.class);
            mockClaimRepo = mock(InvestigationClaimRepository.class);
            mockTimelineRepo = mock(InvestigationTimelineRepository.class);
            mockHypothesisRepo = mock(InvestigationHypothesisRepository.class);
            mapper = new InvestigationPersistenceMapper();

            coordinator = new InvestigationPersistenceCoordinator(
                    mockInvRepo, mockEvidenceRepo, mockClaimRepo,
                    mockTimelineRepo, mockHypothesisRepo, null, null, mapper
            );

            mockGitCollector = mock(GitCollector.class);
            when(mockGitCollector.collectCommits(any(), anyInt())).thenReturn(Collections.emptyList());
            mockStackTraceParser = mock(StackTraceParser.class);
            when(mockStackTraceParser.parse(any())).thenReturn(Optional.empty());
            mockLogAnalyzer = mock(LogAnalyzer.class);
            mockTimelineBuilder = mock(TimelineBuilder.class);
            when(mockTimelineBuilder.buildTimeline(any(), any(), any(), any(), any())).thenReturn(Collections.emptyList());
            mockCorrelationEngine = mock(com.traceback.core.correlation.CorrelationEngine.class);
            when(mockCorrelationEngine.correlate(any(), any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(new com.traceback.core.correlation.CorrelationResult(
                            Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyMap(), Collections.emptyMap(),
                            Collections.emptyMap(), Collections.emptyList(), false
                    ));
            evidenceRegistry = new ConcreteEvidenceRegistry();

            service = new InvestigationService(
                    mockGitCollector, mockStackTraceParser, mockLogAnalyzer,
                    mockTimelineBuilder, mockCorrelationEngine, evidenceRegistry
            );
            service.setPersistenceCoordinator(coordinator);
            service.setInvestigationRepository(mockInvRepo);
            service.setEvidenceRepository(mockEvidenceRepo);
            service.setClaimRepository(mockClaimRepo);
            service.setTimelineRepository(mockTimelineRepo);
            service.setHypothesisRepository(mockHypothesisRepo);
        }

        @Test
        @DisplayName("Scenario 1: Successful database persistence reports durable success")
        void testSuccessfulPersistence_ReportsDurableSuccess() {
            InvestigationResult result = service.startInvestigation(
                    null, "java.lang.NullPointerException", null, null,
                    "order-service", "production", 60, 50
            );

            assertThat(result.persistedDurable()).isTrue();
            assertThat(result.persistenceStatus()).isEqualTo("DURABLE");

            verify(mockInvRepo).save(any(InvestigationEntity.class));
        }

        @Test
        @DisplayName("Scenario 2: Database failure is NOT presented as durable success")
        void testDatabaseFailure_NotPresentedAsDurableSuccess() {
            // Simulate PostgreSQL connection failure during save
            doThrow(new RuntimeException("PostgreSQL connection timeout: connection pool exhausted"))
                    .when(mockInvRepo).save(any(InvestigationEntity.class));

            InvestigationResult result = service.startInvestigation(
                    null, "java.lang.NullPointerException", null, null,
                    "order-service", "production", 60, 50
            );

            // Must NOT report durable success
            assertThat(result.persistedDurable()).isFalse();
            assertThat(result.persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");
        }

        @Test
        @DisplayName("Scenario 3: In-memory fallback is clearly distinguished from database persistence")
        void testInMemoryFallback_ClearlyDistinguished() {
            doThrow(new RuntimeException("Database offline"))
                    .when(mockInvRepo).save(any(InvestigationEntity.class));

            InvestigationResult result = service.startInvestigation(
                    null, "java.lang.RuntimeException", null, null,
                    "payment-service", "production", 60, 50
            );

            // Retrieval from in-memory cache directly
            Optional<InvestigationResult> retrievedOpt = service.getInvestigation(result.investigationId());
            assertThat(retrievedOpt).isPresent();
            InvestigationResult retrieved = retrievedOpt.get();

            assertThat(retrieved.persistedDurable()).isFalse();
            assertThat(retrieved.persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");
        }

        @Test
        @DisplayName("Scenario 4: Retrieval after simulated process/cache loss does not falsely claim ephemeral result exists durably")
        void testRetrievalAfterCacheLoss_DoesNotFalselyReturnEphemeralAsDurable() {
            doThrow(new RuntimeException("Database disk full"))
                    .when(mockInvRepo).save(any(InvestigationEntity.class));

            InvestigationResult result = service.startInvestigation(
                    null, "java.lang.RuntimeException", null, null,
                    "payment-service", "production", 60, 50
            );
            UUID invId = result.investigationId();

            // Simulate application restart / cache loss by clearing the in-memory store
            // (When DB save failed, nothing exists in mockInvRepo)
            when(mockInvRepo.findById(invId)).thenReturn(Optional.empty());

            // Create a fresh service instance with empty in-memory store to simulate clean process reboot
            InvestigationService rebootedService = new InvestigationService(
                    mockGitCollector, mockStackTraceParser, mockLogAnalyzer,
                    mockTimelineBuilder, mockCorrelationEngine, new ConcreteEvidenceRegistry()
            );
            rebootedService.setPersistenceCoordinator(coordinator);
            rebootedService.setInvestigationRepository(mockInvRepo);

            Optional<InvestigationResult> recoveredOpt = rebootedService.getInvestigation(invId);

            // Ephemeral result is cleanly absent from durable storage: 404 / empty, not a phantom durable record
            assertThat(recoveredOpt).isEmpty();
        }

        @Test
        @DisplayName("Scenario 5: Cold retrieval from database marks record as durable")
        void testColdRetrievalFromDatabase_MarksAsDurable() {
            UUID invId = UUID.randomUUID();
            InvestigationEntity dbEntity = new InvestigationEntity(
                    invId, InvestigationMode.RUNTIME_FAILURE, "billing-service", "prod",
                    "/repo", "COMPLETED", "Summary", "CONFIRMED", 0.95, "REP-1"
            );
            when(mockInvRepo.findById(invId)).thenReturn(Optional.of(dbEntity));

            // Query cold (empty in-memory cache)
            Optional<InvestigationResult> retrievedOpt = service.getInvestigation(invId);

            assertThat(retrievedOpt).isPresent();
            InvestigationResult retrieved = retrievedOpt.get();
            assertThat(retrieved.persistedDurable()).isTrue();
            assertThat(retrieved.persistenceStatus()).isEqualTo("DURABLE");
            assertThat(retrieved.serviceName()).isEqualTo("billing-service");
        }

        @Test
        @DisplayName("Scenario 6: Coordinated investigation persistence rolls back when secondary entity fails")
        void testCoordinatedInvestigation_RollsBackOnSecondaryEntityFailure() {
            UUID invId = UUID.randomUUID();
            InvestigationResult result = new InvestigationResult(
                    invId, "svc", "prod", Instant.now(), Instant.now(), "COMPLETED",
                    1, 1, null, Collections.emptyList(),
                    List.of(EvidenceItem.create("EVD-1", EvidenceType.COMMIT, "GIT", "c1", Instant.now(), "Commit",
                            Map.of("investigationId", invId.toString(), "originatingInvestigationId", invId.toString()))),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap()
            );

            // Evidence save throws exception
            doThrow(new DataIntegrityViolationException("Constraint violation on evidence"))
                    .when(mockEvidenceRepo).save(any());

            assertThatThrownBy(() -> coordinator.persistInvestigationCoordinated(result, "/repo"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
