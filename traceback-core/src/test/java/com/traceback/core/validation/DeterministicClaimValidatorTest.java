package com.traceback.core.validation;

import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicClaimValidatorTest {

    private DeterministicClaimValidator validator;
    private EvidenceRegistry registry;
    private UUID investigationId;

    @BeforeEach
    void setUp() {
        validator = new DeterministicClaimValidator();
        registry = new ConcreteEvidenceRegistry();
        investigationId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should reject claim with no evidence IDs")
    void shouldRejectClaimWithNoEvidenceIds() {
        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-001",
                investigationId,
                "Commit c1a89b3 introduced the payment calculation bug",
                Collections.emptyList(),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(result.explanation()).contains("cites zero supporting evidence IDs");
    }

    @Test
    @DisplayName("Should reject claim citing nonexistent evidence IDs")
    void shouldRejectClaimWithNonexistentEvidenceIds() {
        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-002",
                investigationId,
                "Commit c1a89b3 introduced the bug",
                List.of("EVD-NONEXISTENT-999"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(result.explanation()).contains("was not found in the active evidence registry");
    }

    @Test
    @DisplayName("Should reject claim citing evidence belonging to another investigation")
    void shouldRejectClaimWithEvidenceFromAnotherInvestigation() {
        UUID foreignInvestigationId = UUID.randomUUID();
        EvidenceItem foreignItem = EvidenceItem.create(
                "EVD-FOREIGN-101",
                EvidenceType.COMMIT,
                "GIT",
                "sha-foreign",
                Instant.now(),
                "Foreign commit message",
                Map.of("investigationId", foreignInvestigationId.toString())
        );
        registry.registerItem(foreignItem);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-003",
                investigationId,
                "Commit foreign caused the failure",
                List.of("EVD-FOREIGN-101"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(result.explanation()).contains("belongs to a different investigation");
    }

    @Test
    @DisplayName("Should reject claim citing tampered evidence (SHA-256 integrity failure)")
    void shouldRejectClaimWithTamperedEvidence() {
        // Create an item with a forged/corrupted integrity hash
        EvidenceItem tamperedItem = new EvidenceItem(
                "EVD-TAMPERED-01",
                EvidenceType.COMMIT,
                "GIT",
                "sha-123",
                Instant.now(),
                "Legitimate summary",
                Collections.emptyMap(),
                "corrupted_bad_hash_123"
        );
        registry.registerItem(tamperedItem);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-004",
                investigationId,
                "Commit sha-123 caused the crash",
                List.of("EVD-TAMPERED-01"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
        assertThat(result.explanation()).contains("failed cryptographic SHA-256 integrity checksum");
    }

    @Test
    @DisplayName("Should validate fully supported code regression claim")
    void shouldValidateFullySupportedClaim() {
        EvidenceItem gitItem = EvidenceItem.create(
                "EVD-GIT-c1a89b3",
                EvidenceType.COMMIT,
                "GIT",
                "c1a89b3",
                Instant.now(),
                "Refactor discount fee math in PaymentFeeCalculator.java",
                Map.of("investigationId", investigationId.toString())
        );
        EvidenceItem frameItem = EvidenceItem.create(
                "EVD-FRAME-c1a89b3",
                EvidenceType.STACK_FRAME,
                "STACK_TRACE",
                "PaymentFeeCalculator.java:58",
                Instant.now(),
                "Stack frame matches PaymentFeeCalculator.java:58",
                Map.of("investigationId", investigationId.toString())
        );

        registry.registerItem(gitItem);
        registry.registerItem(frameItem);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-005",
                investigationId,
                "Code regression in PaymentFeeCalculator.java matches crash stack frame",
                List.of("EVD-GIT-c1a89b3", "EVD-FRAME-c1a89b3"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(result.explanation()).contains("Supported by 2 verified evidence item(s)");
    }

    @Test
    @DisplayName("Should validate contradicted claim when counter-evidence directly refutes it")
    void shouldValidateContradictedClaim() {
        EvidenceItem gitItem = EvidenceItem.create(
                "EVD-GIT-f881c2b",
                EvidenceType.COMMIT,
                "GIT",
                "f881c2b",
                Instant.now(),
                "Refactor UserProfileAvatar layout and icons",
                Map.of("investigationId", investigationId.toString())
        );
        EvidenceItem contraItem = EvidenceItem.create(
                "EVD-CONTRA-SUBSYS-f881c2b",
                EvidenceType.COMMIT,
                "ANALYSIS",
                "f881c2b",
                Instant.now(),
                "Subsystem contradiction: Commit touched UI with zero database or stack trace overlap",
                Map.of("investigationId", investigationId.toString())
        );

        registry.registerItem(gitItem);
        registry.registerItem(contraItem);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-006",
                investigationId,
                "Commit f881c2b caused database timeout crash",
                List.of("EVD-GIT-f881c2b"),
                List.of("EVD-CONTRA-SUBSYS-f881c2b")
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.CONTRADICTED);
        assertThat(result.explanation()).contains("Claim is refuted by contradictory evidence");
        assertThat(result.explanation()).contains("zero database or stack trace overlap");
    }

    @Test
    @DisplayName("Should validate partially supported claim with non-fatal contradictions")
    void shouldValidatePartiallySupportedClaim() {
        EvidenceItem gitItem = EvidenceItem.create(
                "EVD-GIT-d109b64",
                EvidenceType.COMMIT,
                "GIT",
                "d109b64",
                Instant.now(),
                "Tune database connection timeout to 3s",
                Map.of("investigationId", investigationId.toString(), "key", "spring.datasource.hikari.connection-timeout")
        );
        EvidenceItem cfgItem = EvidenceItem.create(
                "EVD-CFG-d109b64",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.properties#spring.datasource.hikari.connection-timeout",
                Instant.now(),
                "Config delta: 3000ms timeout",
                Map.of("investigationId", investigationId.toString(), "key", "spring.datasource.hikari.connection-timeout")
        );
        EvidenceItem mildContra = EvidenceItem.create(
                "EVD-MILD-CONTRA",
                EvidenceType.COMMIT,
                "ANALYSIS",
                "d109b64",
                Instant.now(),
                "Service reboot was delayed by 10 minutes",
                Map.of("investigationId", investigationId.toString())
        );

        registry.registerItem(gitItem);
        registry.registerItem(cfgItem);
        registry.registerItem(mildContra);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-007",
                investigationId,
                "Configuration change caused timeout failure",
                List.of("EVD-GIT-d109b64", "EVD-CFG-d109b64"),
                List.of("EVD-MILD-CONTRA")
        );

        InvestigationClaim result = validator.validateClaim(claim, investigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.PARTIALLY_SUPPORTED);
        assertThat(result.explanation()).contains("dampened by 1 contradictory evidence observation(s)");
    }

    @Test
    @DisplayName("Should verify valid SHA-256 integrity hash on untampered evidence item")
    void shouldVerifyEvidenceIntegrity() {
        EvidenceItem item = EvidenceItem.create(
                "EVD-VALID-101",
                EvidenceType.COMMIT,
                "GIT",
                "sha-valid",
                Instant.now(),
                "Verified commit",
                Map.of("test", "data")
        );

        assertThat(validator.verifyEvidenceIntegrity(item)).isTrue();
    }
}
