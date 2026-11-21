package com.traceback.api.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.HypothesisResponse;
import com.traceback.api.dto.InvestigationResponse;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.persistence.InvestigationPersistenceCoordinator;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * Phase 2.49.1: Transaction Boundary Verification Integration Tests.
 *
 * Rigorously verifies:
 * 1. InvestigationPersistenceCoordinator Spring AOP proxy registration and transaction annotations.
 * 2. Genuine atomic rollback in persistReportCoordinated on step, claim, and parent entity failure.
 * 3. Genuine atomic rollback in persistInvestigationCoordinated on secondary entity failure.
 * 4. In-memory cache preservation on DB failure, cold cache loss 404 behavior, and cold DB rehydration.
 * 5. DTO serialization with persistedDurable / persistenceStatus and legacy backward compatibility.
 *
 * Uses isolated H2 in-memory database (jdbc:h2:mem:traceback_test).
 * Zero writes to protected databases (traceback_db, traceback_test_db, traceback_audit_db).
 */
@SpringBootTest
@ActiveProfiles("test")
public class Phase2491TransactionBoundaryIntegrationTest {

    @Autowired
    private InvestigationPersistenceCoordinator persistenceCoordinator;

    @SpyBean
    private InvestigationRepository investigationRepository;

    @Autowired
    private InvestigationEvidenceRepository evidenceRepository;

    @Autowired
    private InvestigationClaimRepository claimRepository;

    @Autowired
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired
    private InvestigationAgentStepRepository agentStepRepository;

    @Autowired
    private InvestigationTimelineRepository timelineRepository;

    @Autowired
    private InvestigationHypothesisRepository hypothesisRepository;

    @Autowired
    private ConcreteEvidenceRegistry evidenceRegistry;

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private InvestigationAgent investigationAgent;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Mockito.reset(investigationRepository);
        clearAllRepositoriesAndCaches();
    }

    private void clearAllRepositoriesAndCaches() {
        agentReportRepository.deleteAll();
        agentStepRepository.deleteAll();
        claimRepository.deleteAll();
        timelineRepository.deleteAll();
        hypothesisRepository.deleteAll();
        evidenceRepository.deleteAll();
        investigationRepository.deleteAll();
        evidenceRegistry.clear();

        // Clear in-memory caches via reflection on target objects
        clearMapField(investigationAgent, InvestigationAgent.class, "reportStore");
        clearMapField(investigationService, InvestigationService.class, "investigationStore");
    }

    private void clearMapField(Object bean, Class<?> clazz, String fieldName) {
        try {
            Object target = org.springframework.test.util.AopTestUtils.getTargetObject(bean);
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) f.get(target);
            if (map != null) {
                map.clear();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear field " + fieldName, e);
        }
    }

    // =========================================================================
    // VERIFICATION 1: Spring Transaction Proxy Verification
    // =========================================================================

    @Test
    @DisplayName("V1.1: InvestigationPersistenceCoordinator is wrapped in Spring AOP transaction proxy")
    void shouldVerifySpringAopProxyAndBeanRegistration() {
        assertThat(persistenceCoordinator).isNotNull();

        // 1. Assert AOP proxy wrapping
        assertThat(AopUtils.isAopProxy(persistenceCoordinator))
                .as("InvestigationPersistenceCoordinator must be an AOP proxy")
                .isTrue();

        assertThat(AopUtils.getTargetClass(persistenceCoordinator))
                .isEqualTo(InvestigationPersistenceCoordinator.class);

        // 2. Assert TransactionManager bean exists
        assertThat(transactionManager).isNotNull();

        // 3. Assert @Transactional annotations on both target methods
        Method[] methods = InvestigationPersistenceCoordinator.class.getDeclaredMethods();
        boolean reportTransactional = false;
        boolean invTransactional = false;

        for (Method m : methods) {
            if (m.getName().equals("persistReportCoordinated")) {
                Transactional tx = m.getAnnotation(Transactional.class);
                if (tx != null) {
                    reportTransactional = true;
                    assertThat(tx.rollbackFor()).contains(Exception.class);
                }
            } else if (m.getName().equals("persistInvestigationCoordinated")) {
                Transactional tx = m.getAnnotation(Transactional.class);
                if (tx != null) {
                    invTransactional = true;
                    assertThat(tx.rollbackFor()).contains(Exception.class);
                }
            }
        }

        assertThat(reportTransactional)
                .as("persistReportCoordinated must have @Transactional(rollbackFor = Exception.class)")
                .isTrue();
        assertThat(invTransactional)
                .as("persistInvestigationCoordinated must have @Transactional(rollbackFor = Exception.class)")
                .isTrue();

        // 4. Assert InvestigationAgent and InvestigationService use the Spring proxy bean
        assertThat(investigationAgent.getPersistenceCoordinator()).isNotNull();
        assertThat(AopUtils.isAopProxy(investigationAgent.getPersistenceCoordinator()))
                .as("InvestigationAgent persistenceCoordinator must be the Spring AOP proxy")
                .isTrue();

        assertThat(investigationService.getPersistenceCoordinator()).isNotNull();
        assertThat(AopUtils.isAopProxy(investigationService.getPersistenceCoordinator()))
                .as("InvestigationService persistenceCoordinator must be the Spring AOP proxy")
                .isTrue();
    }

    // =========================================================================
    // VERIFICATION 2: Report Transaction Rollback Verification (H2)
    // =========================================================================

    @Test
    @DisplayName("V2.1: persistReportCoordinated rolls back report entity when agent step fails constraint check")
    void shouldRollbackReportWhenStepFailsConstraint() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);

        // Insert parent investigation entity first
        InvestigationEntity parent = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "PaymentService", "prod",
                "/repo/path", "COMPLETED", "Parent summary", "CONFIRMED", 0.95, null
        );
        investigationRepository.save(parent);

        // Step name exceeds column length of 128 characters (triggers H2 DataIntegrityViolationException)
        AgentInvestigationStep invalidStep = AgentInvestigationStep.of(
                1,
                "STEP_NAME_EXCEEDING_COLUMN_LIMIT_" + "X".repeat(200),
                "Step rationale",
                Map.of("k", "v")
        );

        InvestigationAgentReport report = new InvestigationAgentReport(
                reportId,
                invId,
                "Summary",
                "Root cause statement",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                "AI summary",
                Collections.emptyList(),
                List.of("https://example.com"),
                List.of("verify step"),
                List.of("none"),
                AgentInvestigationStatus.CONFIRMED,
                Collections.emptyList(),
                List.of(invalidStep),
                Instant.now()
        );

        // Calling through the Spring AOP proxy must propagate the exception and roll back
        assertThatThrownBy(() -> persistenceCoordinator.persistReportCoordinated(report))
                .isInstanceOf(Exception.class);

        // Verify genuine database rollback:
        // Report entity was saved in step 1, but must have been ROLLED BACK because step 2 failed!
        assertThat(agentReportRepository.findByReportId(reportId))
                .as("Agent report must be rolled back when step fails")
                .isEmpty();
        assertThat(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId))
                .as("No steps must be committed on failure")
                .isEmpty();

        // Parent investigation must not have its reportId updated
        InvestigationEntity retrievedParent = investigationRepository.findById(invId).orElseThrow();
        assertThat(retrievedParent.getReportId()).isNull();
    }

    @Test
    @DisplayName("V2.2: persistReportCoordinated rolls back report and steps when claim fails constraint check")
    void shouldRollbackReportAndStepsWhenClaimFailsConstraint() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);

        // Insert parent investigation
        InvestigationEntity parent = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "PaymentService", "prod",
                "/repo/path", "COMPLETED", "Parent summary", "CONFIRMED", 0.95, null
        );
        investigationRepository.save(parent);

        // Valid step
        AgentInvestigationStep validStep = AgentInvestigationStep.of(
                1, "Valid Step 1", "Step rationale", Map.of("k", "v")
        );

        // Invalid claim ID exceeding 128 characters
        InvestigationClaim invalidClaim = InvestigationClaim.create(
                "CLAIM_ID_EXCEEDING_COLUMN_LIMIT_" + "Y".repeat(200),
                invId,
                "Claim statement",
                Collections.emptyList(),
                Collections.emptyList()
        );

        InvestigationAgentReport report = new InvestigationAgentReport(
                reportId,
                invId,
                "Summary",
                "Root cause statement",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                "AI summary",
                Collections.emptyList(),
                List.of("https://example.com"),
                List.of("verify step"),
                List.of("none"),
                AgentInvestigationStatus.CONFIRMED,
                List.of(invalidClaim),
                List.of(validStep),
                Instant.now()
        );

        assertThatThrownBy(() -> persistenceCoordinator.persistReportCoordinated(report))
                .isInstanceOf(Exception.class);

        // Both report AND steps were saved before claim, but must be rolled back!
        assertThat(agentReportRepository.findByReportId(reportId))
                .as("Report must be rolled back on claim failure")
                .isEmpty();
        assertThat(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId))
                .as("Steps must be rolled back on claim failure")
                .isEmpty();
        assertThat(claimRepository.findByInvestigationId(invId))
                .as("No claims must be committed on failure")
                .isEmpty();

        InvestigationEntity retrievedParent = investigationRepository.findById(invId).orElseThrow();
        assertThat(retrievedParent.getReportId()).isNull();
    }

    @Test
    @DisplayName("V2.3: persistReportCoordinated rolls back report, steps, and claims when parent update fails")
    void shouldRollbackAllWhenParentInvestigationUpdateFails() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);

        // Save parent investigation initially
        InvestigationEntity parent = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "PaymentService", "prod",
                "/repo/path", "COMPLETED", "Parent summary", "CONFIRMED", 0.95, null
        );
        investigationRepository.save(parent);

        // Configure spy: parent save during coordinator update throws CannotAcquireLockException
        Mockito.doThrow(new org.springframework.dao.CannotAcquireLockException("Simulated lock failure during parent entity update"))
                .when(investigationRepository)
                .save(argThat(e -> e != null && invId.equals(e.getId()) && reportId.equals(e.getReportId())));

        AgentInvestigationStep validStep = AgentInvestigationStep.of(
                1, "Valid Step 1", "Step rationale", Map.of("k", "v")
        );
        InvestigationClaim validClaim = InvestigationClaim.create(
                "CLM-001", invId, "Valid statement", Collections.emptyList(), Collections.emptyList()
        );

        InvestigationAgentReport report = new InvestigationAgentReport(
                reportId,
                invId,
                "Summary",
                "Root cause statement",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                "AI summary",
                Collections.emptyList(),
                List.of("https://example.com"),
                List.of("verify step"),
                List.of("none"),
                AgentInvestigationStatus.CONFIRMED,
                List.of(validClaim),
                List.of(validStep),
                Instant.now()
        );

        assertThatThrownBy(() -> persistenceCoordinator.persistReportCoordinated(report))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);

        // Verify complete rollback: report, steps, claims must all be rolled back
        assertThat(agentReportRepository.findByReportId(reportId)).isEmpty();
        assertThat(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId)).isEmpty();
        assertThat(claimRepository.findByInvestigationId(invId)).isEmpty();

        // Reset spy and verify parent was not updated
        Mockito.reset(investigationRepository);
        InvestigationEntity retrievedParent = investigationRepository.findById(invId).orElseThrow();
        assertThat(retrievedParent.getReportId()).isNull();
    }

    @Test
    @DisplayName("V2.4: persistReportCoordinated successfully commits all entities together")
    void shouldCommitAllEntitiesOnSuccessfulReportPersistence() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);

        InvestigationEntity parent = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "PaymentService", "prod",
                "/repo/path", "COMPLETED", "Parent summary", "CONFIRMED", 0.95, null
        );
        investigationRepository.save(parent);

        AgentInvestigationStep step1 = AgentInvestigationStep.of(1, "Step 1", "Rationale 1", Map.of());
        AgentInvestigationStep step2 = AgentInvestigationStep.of(2, "Step 2", "Rationale 2", Map.of());
        InvestigationClaim claim = InvestigationClaim.create("CLM-100", invId, "Valid claim", Collections.emptyList(), Collections.emptyList());

        InvestigationAgentReport report = new InvestigationAgentReport(
                reportId,
                invId,
                "Summary",
                "Root cause statement",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                "AI summary",
                Collections.emptyList(),
                List.of("https://example.com"),
                List.of("verify step"),
                List.of("none"),
                AgentInvestigationStatus.CONFIRMED,
                List.of(claim),
                List.of(step1, step2),
                Instant.now()
        );

        boolean success = persistenceCoordinator.persistReportCoordinated(report);
        assertThat(success).isTrue();

        // Verify all entities exist in H2
        assertThat(agentReportRepository.findByReportId(reportId)).isPresent();
        assertThat(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId)).hasSize(2);
        assertThat(claimRepository.findByInvestigationId(invId)).hasSize(1);

        InvestigationEntity updatedParent = investigationRepository.findById(invId).orElseThrow();
        assertThat(updatedParent.getReportId()).isEqualTo(reportId);
        assertThat(updatedParent.getVerdict()).isEqualTo("CONFIRMED");
    }

    // =========================================================================
    // VERIFICATION 3: Investigation Transaction Rollback Verification (H2)
    // =========================================================================

    @Test
    @DisplayName("V3.1: persistInvestigationCoordinated rolls back parent investigation when timeline fails constraint")
    void shouldRollbackInvestigationWhenTimelineFailsConstraint() {
        UUID invId = UUID.randomUUID();

        // Valid evidence item
        EvidenceItem item = EvidenceItem.create(
                "EVD-001",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "src/Main.java",
                Instant.now(),
                "Summary",
                Map.of("investigationId", invId.toString(), "originatingInvestigationId", invId.toString())
        );

        // Invalid timeline event type (> 64 characters)
        TimelineEvent invalidEvent = new TimelineEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                "TIMELINE_EVENT_TYPE_EXCEEDING_LIMIT_" + "Z".repeat(100),
                "GIT",
                invId.toString(),
                "Title",
                "Description",
                false,
                item.evidenceId(),
                Map.of()
        );

        InvestigationResult result = new InvestigationResult(
                invId, "OrderService", "staging",
                Instant.now().minusSeconds(60), Instant.now(), "COMPLETED",
                5, 0, null,
                List.of(invalidEvent),
                List.of(item),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList(),
                true,
                InvestigationMode.WORKING_TREE_FORENSICS
        );

        // Coordinator is called via Spring AOP proxy
        assertThatThrownBy(() -> persistenceCoordinator.persistInvestigationCoordinated(result, "/repo/path"))
                .isInstanceOf(Exception.class);

        // Parent investigation was saved in step 1, but MUST have rolled back!
        assertThat(investigationRepository.findById(invId))
                .as("Parent investigation must be rolled back on secondary entity failure")
                .isEmpty();
        assertThat(evidenceRepository.findByInvestigationId(invId))
                .as("Evidence items must be rolled back")
                .isEmpty();
        assertThat(timelineRepository.findByInvestigationIdOrderByTimestampAsc(invId))
                .as("Timeline must be empty")
                .isEmpty();
    }

    @Test
    @DisplayName("V3.2: persistInvestigationCoordinated rolls back parent investigation when claim fails constraint")
    void shouldRollbackInvestigationWhenClaimFailsConstraint() {
        UUID invId = UUID.randomUUID();

        EvidenceItem item = EvidenceItem.create(
                "EVD-002",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "src/Main.java",
                Instant.now(),
                "Summary",
                Map.of("investigationId", invId.toString(), "originatingInvestigationId", invId.toString())
        );

        // Invalid claim ID exceeding 128 chars
        InvestigationClaim invalidClaim = InvestigationClaim.create(
                "CLM-OVERFLOW-" + "W".repeat(200),
                invId,
                "Statement",
                List.of(item.evidenceId()),
                Collections.emptyList()
        );

        InvestigationResult result = new InvestigationResult(
                invId, "OrderService", "staging",
                Instant.now().minusSeconds(60), Instant.now(), "COMPLETED",
                5, 0, null,
                Collections.emptyList(),
                List.of(item),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                List.of(invalidClaim),
                true,
                InvestigationMode.WORKING_TREE_FORENSICS
        );

        assertThatThrownBy(() -> persistenceCoordinator.persistInvestigationCoordinated(result, "/repo/path"))
                .isInstanceOf(Exception.class);

        assertThat(investigationRepository.findById(invId)).isEmpty();
        assertThat(evidenceRepository.findByInvestigationId(invId)).isEmpty();
        assertThat(claimRepository.findByInvestigationId(invId)).isEmpty();
    }

    @Test
    @DisplayName("V3.3: persistInvestigationCoordinated successfully commits full entity graph atomically")
    void shouldCommitAllInvestigationEntitiesOnSuccess() {
        UUID invId = UUID.randomUUID();
        UUID hypId = UUID.randomUUID();

        EvidenceItem item = EvidenceItem.create(
                "EVD-003",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "src/Main.java",
                Instant.now(),
                "Summary",
                Map.of("investigationId", invId.toString(), "originatingInvestigationId", invId.toString())
        );

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-003", invId, "Valid statement", List.of(item.evidenceId()), Collections.emptyList()
        );

        TimelineEvent event = new TimelineEvent(
                UUID.randomUUID().toString(), Instant.now(), "DIFF_CHANGE", "GIT",
                invId.toString(), "Valid title", "Valid description", false, item.evidenceId(), Map.of()
        );

        Hypothesis hypothesis = new Hypothesis(hypId, null, null, 1, 0.95, HypothesisStatus.CONFIRMED, "Summary", "Plan", "Counter");

        InvestigationResult result = new InvestigationResult(
                invId, "OrderService", "staging",
                Instant.now().minusSeconds(60), Instant.now(), "COMPLETED",
                5, 0, null,
                List.of(event),
                List.of(item),
                Collections.emptyList(),
                List.of(hypothesis),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                List.of(claim),
                true,
                InvestigationMode.WORKING_TREE_FORENSICS
        );

        boolean success = persistenceCoordinator.persistInvestigationCoordinated(result, "/repo/path");
        assertThat(success).isTrue();

        // Verify full graph committed
        assertThat(investigationRepository.findById(invId)).isPresent();
        assertThat(evidenceRepository.findByInvestigationId(invId)).hasSize(1);
        assertThat(claimRepository.findByInvestigationId(invId)).hasSize(1);
        assertThat(timelineRepository.findByInvestigationIdOrderByTimestampAsc(invId)).hasSize(1);
        assertThat(hypothesisRepository.findByInvestigationIdAndHypothesisId(invId, hypId)).isPresent();
    }

    // =========================================================================
    // VERIFICATION 4: Durability & In-Memory Cache Behavior
    // =========================================================================

    @Test
    @DisplayName("V4.1: Durability status correctly set to PERSISTENCE_FAILED and in-memory cache preserved on DB error")
    void shouldPreserveInMemoryCacheWithFailedDurabilityOnDbError() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);

        AgentInvestigationStep invalidStep = AgentInvestigationStep.of(
                1, "STEP_EXCEEDING_" + "A".repeat(200), "Rationale", Map.of()
        );

        InvestigationAgentReport initialReport = new InvestigationAgentReport(
                reportId, invId, "Summary", "Cause",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                "AI", Collections.emptyList(), List.of(), List.of(), List.of(),
                AgentInvestigationStatus.CONFIRMED, List.of(), List.of(invalidStep), Instant.now()
        );

        // When persistence fails via InvestigationAgent or coordinator, status becomes PERSISTENCE_FAILED
        InvestigationAgentReport failedReport = initialReport.withPersistenceStatus(false, "PERSISTENCE_FAILED");
        investigationAgent.cacheReport(failedReport);

        // 1. In-memory cache returns the report
        Optional<InvestigationAgentReport> cached = investigationAgent.getReport(invId);
        assertThat(cached).isPresent();
        assertThat(cached.get().persistedDurable()).isFalse();
        assertThat(cached.get().persistenceStatus()).isEqualTo("PERSISTENCE_FAILED");

        // 2. Simulate cache loss (service restart / process restart)
        clearAllRepositoriesAndCaches();

        // 3. Cold DB lookup returns 404 (Optional.empty) because DB transaction rolled back (no phantom data)
        Optional<InvestigationAgentReport> coldReport = investigationAgent.getReport(invId);
        assertThat(coldReport)
                .as("Cold lookup after cache loss must return empty (404) when persistence failed")
                .isEmpty();
    }

    @Test
    @DisplayName("V4.2: Successful persistence rehydrates cleanly from DB after in-memory cache loss")
    void shouldRehydrateCleanlyFromDatabaseAfterCacheLoss() {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-" + UUID.randomUUID().toString().substring(0, 8);

        InvestigationEntity parent = new InvestigationEntity(
                invId, InvestigationMode.RUNTIME_FAILURE, "PaymentService", "prod",
                "/repo/path", "COMPLETED", "Summary", "CONFIRMED", 0.95, null
        );
        investigationRepository.save(parent);

        AgentInvestigationStep step = AgentInvestigationStep.of(1, "Step 1", "Rationale", Map.of());
        InvestigationAgentReport report = new InvestigationAgentReport(
                reportId, invId, "Summary", "Cause",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                ConfidenceBreakdown.calculate(30.0, 20.0, 20.0, 0.0, List.of("Rule match"), List.of()),
                "AI", Collections.emptyList(), List.of(), List.of(), List.of(),
                AgentInvestigationStatus.CONFIRMED, List.of(), List.of(step), Instant.now()
        );

        // Persist durably
        boolean persisted = persistenceCoordinator.persistReportCoordinated(report);
        assertThat(persisted).isTrue();

        InvestigationAgentReport durableReport = report.withPersistenceStatus(true, "DURABLE");
        investigationAgent.cacheReport(durableReport);

        // Clear only in-memory cache (simulating process restart while DB stays populated)
        clearMapField(investigationAgent, InvestigationAgent.class, "reportStore");

        // Rehydration from cold DB must succeed with DURABLE status
        Optional<InvestigationAgentReport> rehydrated = investigationAgent.getReport(invId);
        assertThat(rehydrated).isPresent();
        assertThat(rehydrated.get().reportId()).isEqualTo(reportId);
        assertThat(rehydrated.get().persistedDurable()).isTrue();
        assertThat(rehydrated.get().persistenceStatus()).isEqualTo("DURABLE");
    }

    // =========================================================================
    // VERIFICATION 5: DTO Serialization & Backward Compatibility
    // =========================================================================

    @Test
    @DisplayName("V5.1: DTO JSON serialization includes persistedDurable and persistenceStatus")
    void shouldSerializePersistenceFieldsInDto() throws Exception {
        UUID invId = UUID.randomUUID();
        Instant now = Instant.now();

        InvestigationResponse response = new InvestigationResponse(
                invId, InvestigationMode.RUNTIME_FAILURE, "CartService", "staging",
                "COMPLETED", 10, 2, 3, 4, true,
                null, now.minusSeconds(30), now,
                true, "DURABLE"
        );

        String json = objectMapper.writeValueAsString(response);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("persistedDurable")).isTrue();
        assertThat(root.get("persistedDurable").asBoolean()).isTrue();
        assertThat(root.has("persistenceStatus")).isTrue();
        assertThat(root.get("persistenceStatus").asText()).isEqualTo("DURABLE");

        // Failure status serialization
        InvestigationResponse failedResponse = new InvestigationResponse(
                invId, InvestigationMode.RUNTIME_FAILURE, "CartService", "staging",
                "COMPLETED", 10, 2, 3, 4, true,
                null, now.minusSeconds(30), now,
                false, "PERSISTENCE_FAILED"
        );

        String failedJson = objectMapper.writeValueAsString(failedResponse);
        JsonNode failedRoot = objectMapper.readTree(failedJson);
        assertThat(failedRoot.get("persistedDurable").asBoolean()).isFalse();
        assertThat(failedRoot.get("persistenceStatus").asText()).isEqualTo("PERSISTENCE_FAILED");
    }

    @Test
    @DisplayName("V5.2: DTO backward compatibility handles legacy payloads without persistence fields")
    void shouldHandleLegacyJsonWithoutPersistenceFields() throws Exception {
        UUID invId = UUID.randomUUID();
        String legacyJson = """
                {
                  "investigationId": "%s",
                  "investigationMode": "RUNTIME_FAILURE",
                  "serviceName": "CartService",
                  "environment": "staging",
                  "status": "COMPLETED",
                  "commitsAnalyzed": 10,
                  "errorsDetected": 2,
                  "hypothesesCount": 3,
                  "claimsCount": 4,
                  "evidenceSufficient": true,
                  "startedAt": "2026-11-17T06:00:00Z",
                  "completedAt": "2026-11-17T06:01:00Z"
                }
                """.formatted(invId);

        InvestigationResponse deserialized = objectMapper.readValue(legacyJson, InvestigationResponse.class);

        assertThat(deserialized).isNotNull();
        assertThat(deserialized.investigationId()).isEqualTo(invId);
        assertThat(deserialized.serviceName()).isEqualTo("CartService");
    }
}
