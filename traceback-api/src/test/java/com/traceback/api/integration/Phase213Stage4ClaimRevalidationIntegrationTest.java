package com.traceback.api.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PostgreSqlAvailableCondition.class)
@SpringBootTest
@ActiveProfiles("postgres-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/traceback_audit_db",
        "DB_TEST_NAME=traceback_audit_db"
})
class Phase213Stage4ClaimRevalidationIntegrationTest {

    private static final UUID TARGET_INVESTIGATION_ID = UUID.fromString("7b9e9117-40da-47f6-a88b-32abc03dd059");
    private static final UUID UNRELATED_INVESTIGATION_ID = UUID.fromString("5c48eb24-a00e-435a-aff8-88879ea113fb");

    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private InvestigationClaimRepository claimRepository;

    @Autowired
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Stage 4 Integration: Execute real revalidateInvestigationClaims against PostgreSQL traceback_audit_db")
    void testRealStage4RevalidationAgainstPostgreSql() throws Exception {
        // Step 1: Record initial state of unrelated investigation
        List<ClaimEntity> unrelatedClaimsBefore = claimRepository.findByInvestigationId(UNRELATED_INVESTIGATION_ID);
        Optional<AgentReportEntity> unrelatedReportBefore = agentReportRepository.findLatestByInvestigationId(UNRELATED_INVESTIGATION_ID);

        // Step 2: Execute real InvestigationService revalidation on target investigation
        List<InvestigationClaim> revalidated = investigationService.revalidateInvestigationClaims(TARGET_INVESTIGATION_ID);

        // Step 3: Verify exactly 17 claims returned
        assertThat(revalidated).hasSize(17);

        // Map domain claims by claimId
        Map<String, InvestigationClaim> domainClaimMap = revalidated.stream()
                .collect(Collectors.toMap(InvestigationClaim::claimId, c -> c));

        // Step 4: Verify the two primary Working-Tree claims are SUPPORTED
        InvestigationClaim wtAnomaly = domainClaimMap.get("CLM-WT-ANOMALY");
        assertThat(wtAnomaly).isNotNull();
        assertThat(wtAnomaly.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(wtAnomaly.explanation())
                .contains("forensically verified observation; deletion process remains unestablished");
        assertThat(wtAnomaly.supportingEvidenceIds()).hasSize(79);

        InvestigationClaim agentPrimary = domainClaimMap.get("CLAIM-AGENT-PRIMARY-7b9e9117");
        assertThat(agentPrimary).isNotNull();
        assertThat(agentPrimary.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(agentPrimary.explanation())
                .contains("forensically verified observation; deletion process remains unestablished");
        assertThat(agentPrimary.supportingEvidenceIds()).hasSize(79);

        // Step 5: Verify the 15 foreign commit claims receive expected validation errors
        long errorCount = revalidated.stream()
                .filter(c -> !c.claimId().equals("CLM-WT-ANOMALY") && !c.claimId().equals("CLAIM-AGENT-PRIMARY-7b9e9117"))
                .filter(c -> c.status() == ClaimValidationStatus.VALIDATION_ERROR)
                .count();
        assertThat(errorCount).isEqualTo(15);

        // Step 6: Verify persisted ClaimEntity records in PostgreSQL traceback_audit_db
        List<ClaimEntity> persistedClaims = claimRepository.findByInvestigationId(TARGET_INVESTIGATION_ID);
        assertThat(persistedClaims).hasSize(17);

        Map<String, ClaimEntity> entityMap = persistedClaims.stream()
                .collect(Collectors.toMap(ClaimEntity::getClaimId, c -> c));

        ClaimEntity persistedWt = entityMap.get("CLM-WT-ANOMALY");
        assertThat(persistedWt.getValidationStatus()).isEqualTo("SUPPORTED");
        assertThat(persistedWt.getValidationExplanation()).contains("forensically verified observation; deletion process remains unestablished");

        ClaimEntity persistedPrimary = entityMap.get("CLAIM-AGENT-PRIMARY-7b9e9117");
        assertThat(persistedPrimary.getValidationStatus()).isEqualTo("SUPPORTED");
        assertThat(persistedPrimary.getValidationExplanation()).contains("forensically verified observation; deletion process remains unestablished");

        // Step 7: Verify agent report validated_claims is updated correctly
        Optional<AgentReportEntity> reportOpt = agentReportRepository.findLatestByInvestigationId(TARGET_INVESTIGATION_ID);
        assertThat(reportOpt).isPresent();
        AgentReportEntity report = reportOpt.get();
        assertThat(report.getReportId()).isEqualTo("REP-e186d4fc");

        List<Map<String, Object>> validatedList = objectMapper.readValue(
                report.getValidatedClaims(),
                new TypeReference<>() {}
        );
        assertThat(validatedList).hasSize(17);

        Map<String, Map<String, Object>> reportMap = validatedList.stream()
                .collect(Collectors.toMap(m -> (String) m.get("claimId"), m -> m));

        assertThat(reportMap.get("CLM-WT-ANOMALY").get("status")).isEqualTo("SUPPORTED");
        assertThat(reportMap.get("CLAIM-AGENT-PRIMARY-7b9e9117").get("status")).isEqualTo("SUPPORTED");

        // Step 8: Verify unrelated investigations remain completely unchanged
        List<ClaimEntity> unrelatedClaimsAfter = claimRepository.findByInvestigationId(UNRELATED_INVESTIGATION_ID);
        assertThat(unrelatedClaimsAfter).hasSameSizeAs(unrelatedClaimsBefore);
        for (int i = 0; i < unrelatedClaimsBefore.size(); i++) {
            assertThat(unrelatedClaimsAfter.get(i).getValidationStatus())
                    .isEqualTo(unrelatedClaimsBefore.get(i).getValidationStatus());
            assertThat(unrelatedClaimsAfter.get(i).getValidationExplanation())
                    .isEqualTo(unrelatedClaimsBefore.get(i).getValidationExplanation());
        }

        Optional<AgentReportEntity> unrelatedReportAfter = agentReportRepository.findLatestByInvestigationId(UNRELATED_INVESTIGATION_ID);
        if (unrelatedReportBefore.isPresent()) {
            assertThat(unrelatedReportAfter).isPresent();
            assertThat(unrelatedReportAfter.get().getValidatedClaims())
                    .isEqualTo(unrelatedReportBefore.get().getValidatedClaims());
        }
    }
}
