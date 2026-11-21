package com.traceback.core.validation;

import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.workingtree.WorkingTreeChangeType;
import com.traceback.core.git.workingtree.WorkingTreeEvidence;
import com.traceback.core.git.workingtree.WorkingTreeStagingState;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Phase2EvidenceOwnershipAndProvenanceRegressionTest {

    private ConcreteEvidenceRegistry registry;
    private DeterministicClaimValidator validator;
    private InvestigationPersistenceMapper mapper;

    private UUID activeInvestigationId;
    private UUID referenceInvestigationId;

    @BeforeEach
    void setUp() {
        registry = new ConcreteEvidenceRegistry();
        validator = new DeterministicClaimValidator();
        mapper = new InvestigationPersistenceMapper();

        activeInvestigationId = UUID.randomUUID();
        referenceInvestigationId = UUID.randomUUID();
    }

    @Test
    @DisplayName("1. Cross-investigation evidence isolation: Evidence created for Investigation A cannot be used by Investigation B")
    void testCrossInvestigationEvidenceIsolation() {
        EvidenceItem foreignItem = EvidenceItem.create(
                "EVD-WT-MISSING-001",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "backend/pom.xml",
                Instant.now(),
                "Working tree missing file: backend/pom.xml",
                Map.of("investigationId", referenceInvestigationId.toString(),
                        "originatingInvestigationId", referenceInvestigationId.toString())
        );
        registry.registerItem(foreignItem, referenceInvestigationId);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-WT-ANOMALY",
                activeInvestigationId,
                "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65).",
                List.of("EVD-WT-MISSING-001"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, activeInvestigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(result.explanation()).contains("different investigation");
    }

    @Test
    @DisplayName("2. Authoritative database ownership during deserialization: Column investigation_id overrides payload")
    void testAuthoritativeDatabaseOwnershipDuringDeserialization() {
        String stalePayload = String.format("{\"relativePath\":\"backend/pom.xml\",\"investigationId\":\"%s\"}", referenceInvestigationId);
        EvidenceItemEntity entity = new EvidenceItemEntity(
                UUID.randomUUID(),
                "EVD-WT-MISSING-002",
                activeInvestigationId, // Authoritative column
                "WORKING_TREE_CHANGE",
                "WORKING_TREE",
                "backend/pom.xml",
                "Working tree missing file: backend/pom.xml",
                stalePayload,
                EvidenceItem.computeFullSha256("EVD-WT-MISSING-002|WORKING_TREE|backend/pom.xml|Working tree missing file: backend/pom.xml")
        );

        EvidenceItem domainFromMapper = mapper.toDomain(entity);
        EvidenceItem domainFromRegistry = registry.toDomain(entity);

        // Authoritative ownership must match database column
        assertThat(domainFromMapper.investigationId()).isEqualTo(activeInvestigationId);
        assertThat(domainFromRegistry.investigationId()).isEqualTo(activeInvestigationId);

        // Stale payload ID preserved as originating provenance
        assertThat(domainFromMapper.originatingInvestigationId()).isEqualTo(referenceInvestigationId);
        assertThat(domainFromRegistry.originatingInvestigationId()).isEqualTo(referenceInvestigationId);
    }

    @Test
    @DisplayName("3. Metadata synchronization during serialization: Serialized payload matches target investigation")
    void testMetadataSynchronizationDuringSerialization() {
        EvidenceItem item = EvidenceItem.create(
                "EVD-WT-MISSING-003",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "src/main/App.java",
                Instant.now(),
                "Working tree missing file: src/main/App.java",
                Map.of("investigationId", referenceInvestigationId.toString())
        );

        // Persist for activeInvestigationId
        EvidenceItemEntity entity = mapper.toEntity(item, activeInvestigationId);

        assertThat(entity.getInvestigationId()).isEqualTo(activeInvestigationId);
        assertThat(entity.getPayload()).contains(activeInvestigationId.toString());
        // Original reference investigation preserved as provenance
        assertThat(entity.getPayload()).contains(referenceInvestigationId.toString());
        assertThat(entity.getPayload()).contains("originatingInvestigationId");
    }

    @Test
    @DisplayName("4. Evidence provenance preservation: Copied/leaked evidence preserves origin and cannot silently validate")
    void testEvidenceProvenancePreservation() {
        EvidenceItem copiedItem = EvidenceItem.create(
                "EVD-WT-MISSING-004",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "README.md",
                Instant.now(),
                "Working tree missing file: README.md",
                Map.of(
                        "investigationId", activeInvestigationId.toString(),
                        "originatingInvestigationId", referenceInvestigationId.toString(),
                        "stagingState", "UNSTAGED",
                        "changeType", "MISSING"
                )
        );
        registry.registerItem(copiedItem, activeInvestigationId);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-WT-ANOMALY",
                activeInvestigationId,
                "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65).",
                List.of("EVD-WT-MISSING-004"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, activeInvestigationId, registry);

        // Copied evidence fails collection provenance validation
        assertThat(result.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(result.explanation()).contains("originated from a different investigation");
    }

    @Test
    @DisplayName("5. Repeated investigations of the same repository: Content IDs match, but ownership remains strictly isolated")
    void testRepeatedInvestigationsOfTheSameRepository() {
        WorkingTreeEvidence wte = new WorkingTreeEvidence(
                null, // generate deterministic ID
                "/path/to/repo",
                "common/config.json",
                WorkingTreeChangeType.MISSING,
                WorkingTreeStagingState.UNSTAGED,
                "d711d65",
                false,
                false,
                false,
                Instant.now(),
                "WORKING_TREE",
                "common",
                true,
                1024L,
                Instant.now()
        );

        EvidenceItem itemA = wte.toEvidenceItem(referenceInvestigationId);
        EvidenceItem itemB = wte.toEvidenceItem(activeInvestigationId);

        // Content-addressable deterministic evidence IDs must match
        assertThat(itemA.evidenceId()).isEqualTo(itemB.evidenceId());

        registry.registerItem(itemA, referenceInvestigationId);
        registry.registerItem(itemB, activeInvestigationId);

        // Lookups scoped to investigation A return itemA
        Optional<EvidenceItem> scopedA = registry.findItemById(itemA.evidenceId(), referenceInvestigationId);
        assertThat(scopedA).isPresent();
        assertThat(scopedA.get().investigationId()).isEqualTo(referenceInvestigationId);

        // Lookups scoped to investigation B return itemB
        Optional<EvidenceItem> scopedB = registry.findItemById(itemB.evidenceId(), activeInvestigationId);
        assertThat(scopedB).isPresent();
        assertThat(scopedB.get().investigationId()).isEqualTo(activeInvestigationId);
    }

    @Test
    @DisplayName("6. Correct validation of independently collected working-tree evidence: Genuine evidence supports CLM-WT-ANOMALY")
    void testCorrectValidationOfIndependentlyCollectedWorkingTreeEvidence() {
        WorkingTreeEvidence wte = new WorkingTreeEvidence(
                null,
                "/path/to/repo",
                "src/main/Service.java",
                WorkingTreeChangeType.MISSING,
                WorkingTreeStagingState.UNSTAGED,
                "d711d65",
                false,
                false,
                false,
                Instant.now(),
                "WORKING_TREE",
                "core",
                true,
                2048L,
                Instant.now()
        );

        EvidenceItem genuineItem = wte.toEvidenceItem(activeInvestigationId);
        registry.registerItem(genuineItem, activeInvestigationId);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-WT-ANOMALY",
                activeInvestigationId,
                "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.",
                List.of(genuineItem.evidenceId()),
                Collections.emptyList()
        );

        InvestigationClaim validated = validator.validateClaim(claim, activeInvestigationId, registry);

        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validated.explanation()).contains("Claim successfully validated");
    }

    @Test
    @DisplayName("7. Rejection of foreign evidence: Non-owned evidence produces VALIDATION_ERROR")
    void testRejectionOfForeignEvidence() {
        EvidenceItem foreignItem = EvidenceItem.create(
                "EVD-GIT-foreign01",
                EvidenceType.COMMIT,
                "GIT",
                "commit:abc1234",
                Instant.now(),
                "Fix memory leak in buffer pool",
                Map.of("investigationId", referenceInvestigationId.toString(),
                        "originatingInvestigationId", referenceInvestigationId.toString())
        );
        registry.registerItem(foreignItem, referenceInvestigationId);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-01",
                activeInvestigationId,
                "Candidate commit abc1234 caused the regression.",
                List.of("EVD-GIT-foreign01"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, activeInvestigationId, registry);

        assertThat(result.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(result.explanation()).contains("different investigation");
    }

    @Test
    @DisplayName("8. Consistent claim statuses: Domain, mapper, and registry serialize identical claim properties")
    void testConsistentClaimStatusesAcrossLayers() {
        InvestigationClaim claim = new InvestigationClaim(
                "CLM-WT-ANOMALY",
                activeInvestigationId,
                "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65).",
                List.of("EVD-WT-001"),
                Collections.emptyList(),
                ClaimValidationStatus.VALIDATION_ERROR,
                "Validation failed: Evidence ID 'EVD-WT-001' belongs to a different investigation.",
                Instant.now()
        );

        var entity = mapper.toEntity(claim, activeInvestigationId, "WORKING_TREE_ANOMALY");
        InvestigationClaim reconstructed = mapper.toDomain(entity);

        assertThat(reconstructed.claimId()).isEqualTo(claim.claimId());
        assertThat(reconstructed.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(reconstructed.explanation()).isEqualTo(claim.explanation());
    }

    @Test
    @DisplayName("9. Active owner with foreign origin: Strict rejection of copied evidence masquerading as active")
    void testActiveOwnerWithForeignOriginRejection() {
        EvidenceItem copiedItem = EvidenceItem.create(
                "EVD-WT-COPIED-001",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "backend/pom.xml",
                Instant.now(),
                "Working tree missing file: backend/pom.xml",
                Map.of(
                        "investigationId", activeInvestigationId.toString(),
                        "originatingInvestigationId", referenceInvestigationId.toString()
                )
        );

        // Domain assertions
        assertThat(copiedItem.isOwnedBy(activeInvestigationId)).isTrue();
        assertThat(copiedItem.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
        assertThat(copiedItem.originatingInvestigationId()).isEqualTo(referenceInvestigationId);

        registry.registerItem(copiedItem, activeInvestigationId);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-WT-ANOMALY",
                activeInvestigationId,
                "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65).",
                List.of("EVD-WT-COPIED-001"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, activeInvestigationId, registry);
        assertThat(result.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(result.explanation()).contains("different investigation (" + referenceInvestigationId + ")");
    }

    @Test
    @DisplayName("10. Foreign owner with active origin: Rejected from active ownership")
    void testForeignOwnerWithActiveOriginRejection() {
        EvidenceItem foreignOwnedItem = EvidenceItem.create(
                "EVD-CFG-MISMATCH-001",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.yml",
                Instant.now(),
                "Configuration delta in commit abc",
                Map.of(
                        "investigationId", referenceInvestigationId.toString(),
                        "originatingInvestigationId", activeInvestigationId.toString()
                )
        );

        // Domain assertions
        assertThat(foreignOwnedItem.isOwnedBy(activeInvestigationId)).isFalse();
        assertThat(foreignOwnedItem.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
        assertThat(foreignOwnedItem.originatingInvestigationId()).isEqualTo(activeInvestigationId);

        registry.registerItem(foreignOwnedItem, referenceInvestigationId);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-01",
                activeInvestigationId,
                "Configuration change caused the issue.",
                List.of("EVD-CFG-MISMATCH-001"),
                Collections.emptyList()
        );

        InvestigationClaim result = validator.validateClaim(claim, activeInvestigationId, registry);
        assertThat(result.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("11. Global evidence policy: Preserves GLOBAL originating provenance and avoids relabeling as genuine")
    void testGlobalEvidencePolicy() {
        EvidenceItem globalItem = EvidenceItem.create(
                "EVD-GLOBAL-FIXTURE",
                EvidenceType.CONFIG_DIFF,
                "ANALYSIS",
                "global/baseline.yml",
                Instant.now(),
                "Global baseline architecture profile",
                Map.of("investigationId", "GLOBAL", "originatingInvestigationId", "GLOBAL")
        );

        assertThat(globalItem.isGlobal()).isTrue();
        assertThat(globalItem.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();

        // Mapper entity serialization preserves GLOBAL originating provenance
        EvidenceItemEntity entity = mapper.toEntity(globalItem, activeInvestigationId);
        assertThat(entity.getInvestigationId()).isEqualTo(activeInvestigationId); // Bound in DB column
        assertThat(entity.getPayload()).contains("\"originatingInvestigationId\":\"GLOBAL\"");

        EvidenceItem domain = mapper.toDomain(entity);
        assertThat(domain.investigationId()).isEqualTo(activeInvestigationId);
        // Persisted under active investigation: carries active UUID owner so isGlobal() is false, preventing disguise
        assertThat(domain.isGlobal()).isFalse();
        assertThat(domain.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
    }

    @Test
    @DisplayName("12. Repeated investigation runs: Registry and mapper idempotency preserves provenance")
    void testRepeatedInvestigationRunsIdempotentRegistry() {
        EvidenceItem item = EvidenceItem.create(
                "EVD-CFG-REPEAT-001",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.yml",
                Instant.now(),
                "Config delta on port",
                Map.of(
                        "investigationId", activeInvestigationId.toString(),
                        "originatingInvestigationId", activeInvestigationId.toString()
                )
        );

        // First registration
        registry.registerItem(item, activeInvestigationId);
        Optional<EvidenceItem> firstLookup = registry.findItemById(item.evidenceId(), activeInvestigationId);
        assertThat(firstLookup).isPresent();

        // Second registration of identical item
        registry.registerItem(item, activeInvestigationId);
        Optional<EvidenceItem> secondLookup = registry.findItemById(item.evidenceId(), activeInvestigationId);
        assertThat(secondLookup).isPresent();

        assertThat(firstLookup.get().integrityHash()).isEqualTo(secondLookup.get().integrityHash());
        assertThat(secondLookup.get().originatingInvestigationId()).isEqualTo(activeInvestigationId);
    }

    @Test
    @DisplayName("13. Active owner with null origin: Requires explicit non-null active origin to be genuinely collected")
    void testActiveOwnerWithNullOriginNotGenuine() {
        EvidenceItem nullOriginItem = EvidenceItem.create(
                "EVD-WT-NULL-ORIGIN",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "backend/pom.xml",
                Instant.now(),
                "Working tree missing file: backend/pom.xml",
                Map.of("investigationId", activeInvestigationId.toString()) // No originatingInvestigationId
        );

        assertThat(nullOriginItem.isOwnedBy(activeInvestigationId)).isTrue();
        assertThat(nullOriginItem.originatingInvestigationId()).isNull();
        assertThat(nullOriginItem.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
    }

    @Test
    @DisplayName("14. Foreign owner with GLOBAL origin: Must never become global or genuine")
    void testForeignOwnerWithGlobalOriginNotGlobalAndNotGenuine() {
        EvidenceItem item = EvidenceItem.create(
                "EVD-DISGUISED-GLOBAL-1",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.yml",
                Instant.now(),
                "Disguised global item",
                Map.of(
                        "investigationId", referenceInvestigationId.toString(),
                        "originatingInvestigationId", "GLOBAL"
                )
        );

        assertThat(item.isGlobal()).isFalse();
        assertThat(item.isOwnedBy(activeInvestigationId)).isFalse();
        assertThat(item.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
    }

    @Test
    @DisplayName("15. GLOBAL owner with foreign origin: Must never become global or genuine")
    void testGlobalOwnerWithForeignOriginNotGlobalAndNotGenuine() {
        EvidenceItem item = EvidenceItem.create(
                "EVD-DISGUISED-GLOBAL-2",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.yml",
                Instant.now(),
                "Disguised foreign item",
                Map.of(
                        "investigationId", "GLOBAL",
                        "originatingInvestigationId", referenceInvestigationId.toString()
                )
        );

        assertThat(item.isGlobal()).isFalse();
        assertThat(item.isOwnedBy(activeInvestigationId)).isFalse();
        assertThat(item.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
    }

    @Test
    @DisplayName("16. Valid active evidence: Both fields match active investigation")
    void testValidActiveEvidenceGenuinelyCollected() {
        EvidenceItem validActive = EvidenceItem.create(
                "EVD-GENUINE-ACTIVE",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "application.yml",
                Instant.now(),
                "Genuine config delta",
                Map.of(
                        "investigationId", activeInvestigationId.toString(),
                        "originatingInvestigationId", activeInvestigationId.toString()
                )
        );

        assertThat(validActive.isGlobal()).isFalse();
        assertThat(validActive.isOwnedBy(activeInvestigationId)).isTrue();
        assertThat(validActive.isGenuinelyCollectedFor(activeInvestigationId)).isTrue();
    }

    @Test
    @DisplayName("17. Malformed UUID metadata: Handled gracefully without throwing exceptions")
    void testMalformedUuidMetadataGracefullyHandled() {
        EvidenceItem malformed = EvidenceItem.create(
                "EVD-MALFORMED",
                EvidenceType.CONFIG_DIFF,
                "ANALYSIS",
                "ref",
                Instant.now(),
                "Malformed metadata values",
                Map.of(
                        "investigationId", "not-a-valid-uuid-12345",
                        "originatingInvestigationId", "also-malformed-xyz"
                )
        );

        assertThat(malformed.investigationId()).isNull();
        assertThat(malformed.originatingInvestigationId()).isNull();
        assertThat(malformed.isGlobal()).isFalse();
        assertThat(malformed.isOwnedBy(activeInvestigationId)).isFalse();
        assertThat(malformed.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
    }

    @Test
    @DisplayName("18. Legacy records with missing provenance: Preserved safely without corruption")
    void testLegacyRecordsWithMissingProvenance() {
        // Legacy record serialized before Phase 2: has payload with only "investigationId"
        String legacyPayload = "{\"relativePath\":\"backend/pom.xml\",\"investigationId\":\"" + referenceInvestigationId + "\"}";
        EvidenceItemEntity legacyEntity = new EvidenceItemEntity(
                UUID.randomUUID(),
                "EVD-LEGACY-001",
                referenceInvestigationId,
                "WORKING_TREE_CHANGE",
                "WORKING_TREE",
                "backend/pom.xml",
                "Working tree missing file",
                legacyPayload,
                EvidenceItem.computeFullSha256("EVD-LEGACY-001|WORKING_TREE|backend/pom.xml|Working tree missing file")
        );

        EvidenceItem domain = mapper.toDomain(legacyEntity);
        assertThat(domain.investigationId()).isEqualTo(referenceInvestigationId);
        assertThat(domain.originatingInvestigationId()).isEqualTo(referenceInvestigationId);
        assertThat(domain.isOwnedBy(referenceInvestigationId)).isTrue();
        assertThat(domain.isGenuinelyCollectedFor(referenceInvestigationId)).isTrue();
        assertThat(domain.isGenuinelyCollectedFor(activeInvestigationId)).isFalse();
    }
}
