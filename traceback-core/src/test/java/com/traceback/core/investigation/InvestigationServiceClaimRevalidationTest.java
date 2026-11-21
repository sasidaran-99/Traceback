package com.traceback.core.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigationServiceClaimRevalidationTest {

    private InvestigationEvidenceRepository evidenceRepository;
    private InvestigationClaimRepository claimRepository;
    private InvestigationAgentReportRepository agentReportRepository;
    private InvestigationPersistenceMapper persistenceMapper;
    private InvestigationService investigationService;

    private final UUID investigationId = UUID.fromString("7b9e9117-40da-47f6-a88b-32abc03dd059");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        evidenceRepository = mock(InvestigationEvidenceRepository.class);
        claimRepository = mock(InvestigationClaimRepository.class);
        agentReportRepository = mock(InvestigationAgentReportRepository.class);
        persistenceMapper = new InvestigationPersistenceMapper(objectMapper);

        investigationService = new InvestigationService();
        investigationService.setClaimValidator(new DeterministicClaimValidator());

        // Use reflection to set the mock repositories and persistenceMapper
        setField(investigationService, "evidenceRepository", evidenceRepository);
        setField(investigationService, "claimRepository", claimRepository);
        setField(investigationService, "agentReportRepository", agentReportRepository);
        setField(investigationService, "persistenceMapper", persistenceMapper);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should revalidate CLM-WT-ANOMALY to SUPPORTED when genuine working tree evidence is present")
    void testRevalidateClaimsWithGenuineWorkingTreeEvidence() throws Exception {
        // Given 79 genuine working tree evidence items
        List<EvidenceItemEntity> evidenceEntities = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();

        for (int i = 1; i <= 79; i++) {
            String evdId = String.format("EVD-WT-MISSING-%010d", i);
            String path = "src/File" + i + ".java";
            String summary = "Working tree missing file: " + path;
            String rawPayload = evdId + "|WORKING_TREE|" + path + "|" + summary;
            String fullHash = sha256(rawPayload);

            String jsonPayload = objectMapper.writeValueAsString(Map.of(
                    "relativePath", path,
                    "changeType", "MISSING",
                    "stagingState", "UNSTAGED",
                    "headCommitSha", "d711d65a3e2fecd40257af992993bfab07fb7a24",
                    "investigationId", investigationId.toString(),
                    "originatingInvestigationId", investigationId.toString()
            ));

            EvidenceItemEntity entity = new EvidenceItemEntity(
                    UUID.randomUUID(),
                    evdId,
                    investigationId,
                    "WORKING_TREE_CHANGE",
                    "WORKING_TREE",
                    path,
                    summary,
                    jsonPayload,
                    fullHash
            );
            evidenceEntities.add(entity);
            evidenceIds.add(evdId);
        }

        when(evidenceRepository.findByInvestigationId(investigationId)).thenReturn(evidenceEntities);

        // Given claims: 1 Working Tree Anomaly claim and 1 Foreign Commit claim
        ClaimEntity wtClaim = new ClaimEntity(
                UUID.randomUUID(),
                "CLM-WT-ANOMALY",
                investigationId,
                "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.",
                "WORKING_TREE_ANOMALY",
                "VALIDATION_ERROR",
                objectMapper.writeValueAsString(evidenceIds),
                "[]",
                "Old validation error"
        );

        ClaimEntity foreignClaim = new ClaimEntity(
                UUID.randomUUID(),
                "CLM-08cae86",
                investigationId,
                "Candidate commit 08cae86 shows weak correlation.",
                "DETERMINISTIC_CORRELATION",
                "VALIDATION_ERROR",
                objectMapper.writeValueAsString(List.of("EVD-GIT-08cae86")),
                "[]",
                "Old validation error"
        );

        when(claimRepository.findByInvestigationId(investigationId)).thenReturn(List.of(wtClaim, foreignClaim));

        AgentReportEntity agentReport = new AgentReportEntity(
                "REP-test-1",
                investigationId,
                "CONFIRMED",
                "Summary",
                "AI Reasoning",
                "{}",
                "[]",
                "[]",
                "[]"
        );
        when(agentReportRepository.findLatestByInvestigationId(investigationId)).thenReturn(Optional.of(agentReport));

        // When revalidation is executed
        List<InvestigationClaim> results = investigationService.revalidateInvestigationClaims(investigationId);

        // Then
        assertThat(results).hasSize(2);

        InvestigationClaim revalidatedWtClaim = results.stream()
                .filter(c -> c.claimId().equals("CLM-WT-ANOMALY"))
                .findFirst().orElseThrow();
        assertThat(revalidatedWtClaim.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(revalidatedWtClaim.explanation()).contains("Supported by 79 verified evidence item(s)");

        InvestigationClaim revalidatedForeignClaim = results.stream()
                .filter(c -> c.claimId().equals("CLM-08cae86"))
                .findFirst().orElseThrow();
        assertThat(revalidatedForeignClaim.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(revalidatedForeignClaim.explanation()).contains("was not found in the active evidence registry");

        // Verify entities were updated and saved
        verify(claimRepository).save(wtClaim);
        verify(claimRepository).save(foreignClaim);
        assertThat(wtClaim.getValidationStatus()).isEqualTo("SUPPORTED");
        assertThat(foreignClaim.getValidationStatus()).isEqualTo("VALIDATION_ERROR");

        verify(agentReportRepository).save(agentReport);
        assertThat(agentReport.getValidatedClaims()).contains("CLM-WT-ANOMALY");
    }
}
