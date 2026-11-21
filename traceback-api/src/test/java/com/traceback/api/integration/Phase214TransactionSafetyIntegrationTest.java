package com.traceback.api.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.ClaimValidator;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(PostgreSqlAvailableCondition.class)
@SpringBootTest
@ActiveProfiles("postgres-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/traceback_audit_db",
        "DB_TEST_NAME=traceback_audit_db"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase214TransactionSafetyIntegrationTest {

    private static final UUID TARGET_INVESTIGATION_ID = UUID.fromString("7b9e9117-40da-47f6-a88b-32abc03dd059");
    private static final UUID UNRELATED_INVESTIGATION_ID = UUID.fromString("5c48eb24-a00e-435a-aff8-88879ea113fb");

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private InvestigationClaimRepository claimRepository;

    @Autowired
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired
    private InvestigationEvidenceRepository evidenceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    @DisplayName("Scenario 1: Failure before claim revalidation leaves database completely untouched")
    void test1_FailureBeforeRevalidation_LeavesDatabaseUntouched() {
        // Record baseline claim statuses directly from PostgreSQL
        List<Map<String, Object>> baselineClaims = jdbcTemplate.queryForList(
                "SELECT claim_id, validation_status, validation_explanation FROM investigation_claims WHERE investigation_id = ?",
                TARGET_INVESTIGATION_ID
        );
        assertThat(baselineClaims).hasSize(17);

        // Precondition check failure: Pass null investigationId
        assertThatThrownBy(() -> investigationService.revalidateInvestigationClaims(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("investigationId cannot be null");

        // Verify claims in PostgreSQL are 100% unchanged
        List<Map<String, Object>> claimsAfter = jdbcTemplate.queryForList(
                "SELECT claim_id, validation_status, validation_explanation FROM investigation_claims WHERE investigation_id = ?",
                TARGET_INVESTIGATION_ID
        );
        assertThat(claimsAfter).isEqualTo(baselineClaims);
    }

    @Test
    @Order(2)
    @DisplayName("Scenario 2: Failure after 5 claims processed triggers clean rollback in PostgreSQL")
    void test2_FailureAfterSomeClaimsProcessed_RollsBackAll() {
        // Baseline: CLM-WT-ANOMALY is not yet SUPPORTED (it has VALIDATION_ERROR or initial state)
        Map<String, String> statusesBefore = getClaimStatusMap();

        // Inject a faulty ClaimValidator that fails on claim #6
        AtomicInteger count = new AtomicInteger(0);
        investigationService.setClaimValidator(new DeterministicClaimValidator() {
            @Override
            public InvestigationClaim validateClaim(InvestigationClaim claim, UUID investigationId, EvidenceRegistry registry) {
                int c = count.incrementAndGet();
                if (c == 6) {
                    throw new RuntimeException("INJECTED FAILURE ON CLAIM #6: Simulating mid-stream claim failure");
                }
                return super.validateClaim(claim, investigationId, registry);
            }
        });

        // Execute revalidation; expect failure on 6th claim
        assertThatThrownBy(() -> investigationService.revalidateInvestigationClaims(TARGET_INVESTIGATION_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("INJECTED FAILURE ON CLAIM #6");

        // Restore real validator
        investigationService.setClaimValidator(new DeterministicClaimValidator());

        // Verify database state outside the failed transaction
        Map<String, String> statusesAfter = getClaimStatusMap();

        // Critical assertion: The 5 claims that were validated and saved in memory prior to claim 6
        // MUST NOT be committed in PostgreSQL. Rollback must have reverted them!
        assertThat(statusesAfter)
                .as("All 17 claims in PostgreSQL must remain identical to pre-failure state due to transaction rollback")
                .isEqualTo(statusesBefore);
    }

    @Test
    @Order(3)
    @DisplayName("Scenario 3: Failure during report update triggers transaction rollback of all claims")
    void test3_FailureDuringReportUpdate_RollsBackAllClaims() {
        Map<String, String> statusesBefore = getClaimStatusMap();

        // Simulate an outer transactional runner that checks report after revalidateInvestigationClaims
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("SimulatedFailingRunnerTx");
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            // Revalidate claims
            investigationService.revalidateInvestigationClaims(TARGET_INVESTIGATION_ID);

            // Injected failure: Report assertion failure
            throw new IllegalStateException("INJECTED REPORT VERIFICATION FAILURE: Simulating report discrepancy");
        } catch (Exception e) {
            transactionManager.rollback(status);
        }

        // Verify all claims in PostgreSQL were rolled back
        Map<String, String> statusesAfter = getClaimStatusMap();
        assertThat(statusesAfter)
                .as("Claims must be rolled back when the enclosing transaction encounters report failure")
                .isEqualTo(statusesBefore);
    }

    @Test
    @Order(4)
    @DisplayName("Scenario 4: Transaction rollback after injected database constraint failure")
    void test4_TransactionRollbackAfterDatabaseError() {
        Map<String, String> statusesBefore = getClaimStatusMap();

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("SimulatedDbErrorTx");
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            investigationService.revalidateInvestigationClaims(TARGET_INVESTIGATION_ID);
            // Inject SQL error inside transaction
            jdbcTemplate.execute("INSERT INTO investigation_claims (id, investigation_id, claim_id) VALUES ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 'INVALID')");
        } catch (Exception e) {
            transactionManager.rollback(status);
        }

        Map<String, String> statusesAfter = getClaimStatusMap();
        assertThat(statusesAfter).isEqualTo(statusesBefore);
    }

    @Test
    @Order(5)
    @DisplayName("Scenario 5: Retry after rollback succeeds cleanly and updates database")
    void test5_RetryAfterRollback_SucceedsCleanly() throws Exception {
        // Ensure deterministic validator is active
        investigationService.setClaimValidator(new DeterministicClaimValidator());

        // Execute genuine revalidation
        List<InvestigationClaim> revalidated = investigationService.revalidateInvestigationClaims(TARGET_INVESTIGATION_ID);

        // Verify return value
        assertThat(revalidated).hasSize(17);
        Map<String, InvestigationClaim> domainMap = revalidated.stream()
                .collect(Collectors.toMap(InvestigationClaim::claimId, c -> c));

        assertThat(domainMap.get("CLM-WT-ANOMALY").status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(domainMap.get("CLAIM-AGENT-PRIMARY-7b9e9117").status()).isEqualTo(ClaimValidationStatus.SUPPORTED);

        // Verify PostgreSQL database persisted statuses
        Map<String, String> persistedStatuses = getClaimStatusMap();
        assertThat(persistedStatuses.get("CLM-WT-ANOMALY")).isEqualTo("SUPPORTED");
        assertThat(persistedStatuses.get("CLAIM-AGENT-PRIMARY-7b9e9117")).isEqualTo("SUPPORTED");

        long errorCount = persistedStatuses.values().stream().filter(s -> s.equals("VALIDATION_ERROR")).count();
        assertThat(errorCount).isEqualTo(15);

        // Verify agent report
        Optional<AgentReportEntity> reportOpt = agentReportRepository.findLatestByInvestigationId(TARGET_INVESTIGATION_ID);
        assertThat(reportOpt).isPresent();
        List<Map<String, Object>> validatedList = objectMapper.readValue(
                reportOpt.get().getValidatedClaims(),
                new TypeReference<>() {}
        );
        assertThat(validatedList).hasSize(17);
    }

    @Test
    @Order(6)
    @DisplayName("Scenario 6: Repeated successful revalidation is completely idempotent")
    void test6_RepeatedSuccessfulRevalidation_IsIdempotent() throws Exception {
        // Record state after first successful run
        Map<String, String> statusesRun1 = getClaimStatusMap();
        Optional<AgentReportEntity> report1 = agentReportRepository.findLatestByInvestigationId(TARGET_INVESTIGATION_ID);

        // Run revalidation a second time
        List<InvestigationClaim> revalidatedRun2 = investigationService.revalidateInvestigationClaims(TARGET_INVESTIGATION_ID);
        assertThat(revalidatedRun2).hasSize(17);

        // Record state after second run
        Map<String, String> statusesRun2 = getClaimStatusMap();
        Optional<AgentReportEntity> report2 = agentReportRepository.findLatestByInvestigationId(TARGET_INVESTIGATION_ID);

        // Compare run 1 vs run 2: Must be completely identical in domain statuses
        assertThat(statusesRun2).isEqualTo(statusesRun1);

        List<Map<String, Object>> list1 = objectMapper.readValue(report1.get().getValidatedClaims(), new TypeReference<>() {});
        List<Map<String, Object>> list2 = objectMapper.readValue(report2.get().getValidatedClaims(), new TypeReference<>() {});
        assertThat(list2).hasSameSizeAs(list1);

        Map<String, Object> map1 = list1.stream().collect(Collectors.toMap(m -> (String) m.get("claimId"), m -> m.get("status")));
        Map<String, Object> map2 = list2.stream().collect(Collectors.toMap(m -> (String) m.get("claimId"), m -> m.get("status")));
        assertThat(map2).isEqualTo(map1);
    }

    private Map<String, String> getClaimStatusMap() {
        List<ClaimEntity> entities = claimRepository.findByInvestigationId(TARGET_INVESTIGATION_ID);
        return entities.stream().collect(Collectors.toMap(ClaimEntity::getClaimId, ClaimEntity::getValidationStatus));
    }
}
