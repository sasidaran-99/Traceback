package com.traceback.core.git.workingtree;

import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingTreeClaimValidationTest {

    private DeterministicClaimValidator validator;
    private EvidenceRegistry registry;
    private UUID investigationId;
    private EvidenceItem missingFileEvidence;

    @BeforeEach
    void setUp() {
        validator = new DeterministicClaimValidator();
        registry = new ConcreteEvidenceRegistry();
        investigationId = UUID.randomUUID();

        WorkingTreeEvidence wte = new WorkingTreeEvidence(
                "EVD-WT-MISSING-backend-001",
                "/repo",
                "backend/src/main/java/com/tacitiq/Application.java",
                WorkingTreeChangeType.MISSING,
                WorkingTreeStagingState.UNSTAGED,
                "d711d65",
                false,
                false,
                false,
                Instant.now(),
                "WORKING_TREE",
                "backend/main",
                true,
                null,
                null
        );
        missingFileEvidence = wte.toEvidenceItem(investigationId);
        registry.registerItem(missingFileEvidence);
    }

    @Test
    @DisplayName("Should reject claims that confuse unstaged working tree missing files with committed deletions")
    void shouldRejectConfusingWorkingTreeWithCommittedDeletion() {
        InvestigationClaim falseClaim1 = InvestigationClaim.create(
                "CLM-001",
                investigationId,
                "Commit d711d65 deleted the backend application files.",
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );

        InvestigationClaim validated1 = validator.validateClaim(falseClaim1, investigationId, registry);
        assertThat(validated1.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(validated1.explanation())
                .contains("Claim asserts committed deletion in repository history, but cited evidence items are uncommitted working tree changes");

        InvestigationClaim falseClaim2 = InvestigationClaim.create(
                "CLM-002",
                investigationId,
                "Committed deletion of 79 backend files in git history.",
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );

        InvestigationClaim validated2 = validator.validateClaim(falseClaim2, investigationId, registry);
        assertThat(validated2.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(validated2.explanation())
                .contains("Claim asserts committed deletion in repository history, but cited evidence items are uncommitted working tree changes");
    }

    @Test
    @DisplayName("Should reject claims asserting an unverified deletion process without system audit logs")
    void shouldRejectUnverifiedDeletionProcessClaims() {
        // OneDrive claim
        InvestigationClaim oneDriveClaim = InvestigationClaim.create(
                "CLM-003",
                investigationId,
                "Files were deleted by OneDrive cloud sync client in background.",
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );
        InvestigationClaim validatedOneDrive = validator.validateClaim(oneDriveClaim, investigationId, registry);
        assertThat(validatedOneDrive.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(validatedOneDrive.explanation())
                .contains("Claim asserts specific deletion process without corroborating system audit log");

        // Antivirus claim
        InvestigationClaim avClaim = InvestigationClaim.create(
                "CLM-004",
                investigationId,
                "Antivirus or Windows Defender quarantined the Java files.",
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );
        InvestigationClaim validatedAv = validator.validateClaim(avClaim, investigationId, registry);
        assertThat(validatedAv.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(validatedAv.explanation())
                .contains("Claim asserts specific deletion process without corroborating system audit log");

        // rm -rf / user deleted claim
        InvestigationClaim rmClaim = InvestigationClaim.create(
                "CLM-005",
                investigationId,
                "User deleted the backend folder using rm -rf script.",
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );
        InvestigationClaim validatedRm = validator.validateClaim(rmClaim, investigationId, registry);
        assertThat(validatedRm.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(validatedRm.explanation())
                .contains("Claim asserts specific deletion process without corroborating system audit log");

        // IDE claim
        InvestigationClaim ideClaim = InvestigationClaim.create(
                "CLM-006",
                investigationId,
                "IDE re-indexing wiped the source files.",
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );
        InvestigationClaim validatedIde = validator.validateClaim(ideClaim, investigationId, registry);
        assertThat(validatedIde.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(validatedIde.explanation())
                .contains("Claim asserts specific deletion process without corroborating system audit log");
    }

    @Test
    @DisplayName("Should validate and SUPPORT factual, evidence-grounded working tree anomaly claims")
    void shouldSupportFactualWorkingTreeClaims() {
        String factualStatement = "The repository contains 79 missing tracked files in its current working tree. All 79 are present in HEAD. No committed deletion was identified. The process responsible for their disappearance is not established.";

        InvestigationClaim factualClaim = InvestigationClaim.create(
                "CLM-WT-ANOMALY",
                investigationId,
                factualStatement,
                List.of(missingFileEvidence.evidenceId()),
                Collections.emptyList()
        );

        InvestigationClaim validated = validator.validateClaim(factualClaim, investigationId, registry);
        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validated.explanation()).contains("Claim successfully validated");
    }
}
