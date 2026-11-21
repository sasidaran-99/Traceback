package com.traceback.core.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 9.8.4 Comprehensive Evidence Ownership & Claim Validation Test Suite.
 * Verifies Test Scenarios A through J.
 */
class Phase984EvidenceOwnershipAndClaimValidationTest {

    private DeterministicClaimValidator validator;
    private ConcreteEvidenceRegistry registry;
    private InvestigationEvidenceRepository mockRepo;
    private ObjectMapper objectMapper;

    private UUID invA;
    private UUID invB;

    @BeforeEach
    void setUp() {
        validator = new DeterministicClaimValidator();
        mockRepo = Mockito.mock(InvestigationEvidenceRepository.class);
        objectMapper = new ObjectMapper();
        registry = new ConcreteEvidenceRegistry(mockRepo, objectMapper);

        invA = UUID.randomUUID();
        invB = UUID.randomUUID();
    }

    @Test
    @DisplayName("Test A: Two investigations sharing same commit SHA retain separate records in itemsByInvestigationId")
    void testA_sharedCommitShaRetainsSeparateRecords() {
        String commonSha = "12ef988";
        String evdId = "EVD-GIT-" + commonSha;

        EvidenceItem itemA = EvidenceItem.create(
                evdId,
                EvidenceType.COMMIT,
                "GIT",
                commonSha,
                Instant.now(),
                "Commit in Inv A",
                Map.of("author", "Alice", "investigationId", invA.toString())
        );

        EvidenceItem itemB = EvidenceItem.create(
                evdId,
                EvidenceType.COMMIT,
                "GIT",
                commonSha,
                Instant.now(),
                "Commit in Inv B",
                Map.of("author", "Bob", "investigationId", invB.toString())
        );

        registry.registerItem(itemA, invA);
        registry.registerItem(itemB, invB);

        Optional<EvidenceItem> retrievedA = registry.findItemById(evdId, invA);
        Optional<EvidenceItem> retrievedB = registry.findItemById(evdId, invB);

        assertThat(retrievedA).isPresent();
        assertThat(retrievedA.get().summary()).isEqualTo("Commit in Inv A");
        assertThat(retrievedA.get().metadata().get("author")).isEqualTo("Alice");

        assertThat(retrievedB).isPresent();
        assertThat(retrievedB.get().summary()).isEqualTo("Commit in Inv B");
        assertThat(retrievedB.get().metadata().get("author")).isEqualTo("Bob");
    }

    @Test
    @DisplayName("Test B: Investigation A referencing Investigation B's evidence is rejected as UNSUPPORTED with foreign message")
    void testB_referencingForeignEvidenceIsRejected() {
        String evdId = "EVD-GIT-ee51c22";
        EvidenceItem itemB = EvidenceItem.create(
                evdId,
                EvidenceType.COMMIT,
                "GIT",
                "ee51c22",
                Instant.now(),
                "Fix timeout in B",
                Map.of("investigationId", invB.toString())
        );
        registry.registerItem(itemB, invB);

        InvestigationClaim claimA = InvestigationClaim.create(
                "CLM-A-001",
                invA,
                "Investigation A asserts commit ee51c22 caused regression",
                List.of(evdId),
                Collections.emptyList()
        );

        InvestigationClaim validated = validator.validateClaim(claimA, invA, registry);

        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(validated.explanation())
                .contains("belongs to a different investigation")
                .contains(invB.toString())
                .contains(invA.toString());
    }

    @Test
    @DisplayName("Test C: Investigation A referencing its own valid evidence succeeds as SUPPORTED")
    void testC_referencingOwnValidEvidenceSucceeds() {
        String sha = "12ef988";
        String gitId = "EVD-GIT-" + sha;
        String frameId = "EVD-FRAME-" + sha + "-OrderService.java";
        String cfgId = "EVD-CFG-" + sha;

        EvidenceItem git = EvidenceItem.create(gitId, EvidenceType.COMMIT, "GIT", sha, Instant.now(), "Update orders", Map.of("investigationId", invA.toString()));
        EvidenceItem frame = EvidenceItem.create(frameId, EvidenceType.STACK_FRAME, "STACK_TRACE", "OrderService.java:42", Instant.now(), "Stack frame matches OrderService.java", Map.of("investigationId", invA.toString()));
        EvidenceItem cfg = EvidenceItem.create(cfgId, EvidenceType.CONFIG_DIFF, "GIT", sha + ":config", Instant.now(), "Config delta timeout changed", Map.of("investigationId", invA.toString(), "key", "timeout"));

        registry.registerItem(git, invA);
        registry.registerItem(frame, invA);
        registry.registerItem(cfg, invA);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-" + sha,
                invA,
                "Commit " + sha + " introduced code regression in OrderService.java",
                List.of(gitId, frameId, cfgId),
                Collections.emptyList()
        );

        InvestigationClaim validated = validator.validateClaim(claim, invA, registry);

        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validated.explanation()).contains("Supported by 3 verified evidence item(s)");
    }

    @Test
    @DisplayName("Test D: Agent primary claims obey identical ownership rules")
    void testD_agentPrimaryClaimsObeyOwnershipRules() {
        String sha = "ee51c22";
        String gitId = "EVD-GIT-" + sha;
        String cfgId = "EVD-CFG-" + sha;

        // Registered under Inv A
        EvidenceItem gitA = EvidenceItem.create(gitId, EvidenceType.COMMIT, "GIT", sha, Instant.now(), "Fix config", Map.of("investigationId", invA.toString()));
        EvidenceItem cfgA = EvidenceItem.create(cfgId, EvidenceType.CONFIG_DIFF, "GIT", sha + ":config", Instant.now(), "Config change timeout=5000", Map.of("investigationId", invA.toString(), "key", "timeout"));
        registry.registerItem(gitA, invA);
        registry.registerItem(cfgA, invA);

        // Valid primary claim under Inv A
        InvestigationClaim topClaimA = InvestigationClaim.create(
                "CLAIM-AGENT-PRIMARY-" + invA.toString().substring(0, 8),
                invA,
                "Commit " + sha + " is a probable contributing change for payment-service failure.",
                List.of(gitId, cfgId),
                Collections.emptyList()
        );

        InvestigationClaim validatedA = validator.validateClaim(topClaimA, invA, registry);
        assertThat(validatedA.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);

        // If Inv B tries to validate an agent primary claim pointing to Inv A's evidence
        InvestigationClaim topClaimB = InvestigationClaim.create(
                "CLAIM-AGENT-PRIMARY-" + invB.toString().substring(0, 8),
                invB,
                "Commit " + sha + " is a probable contributing change for payment-service failure.",
                List.of(gitId, cfgId),
                Collections.emptyList()
        );

        InvestigationClaim validatedB = validator.validateClaim(topClaimB, invB, registry);
        assertThat(validatedB.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(validatedB.explanation()).contains("belongs to a different investigation");
    }

    @Test
    @DisplayName("Test E: Legacy unscoped method works for safe callers, but not for scoped validation")
    void testE_legacyUnscopedMethodBehavior() {
        String evdId = "EVD-LEGACY-01";
        EvidenceItem legacy = EvidenceItem.create(
                evdId,
                EvidenceType.COMMIT,
                "GIT",
                "sha-leg",
                Instant.now(),
                "Legacy item",
                Map.of("investigationId", invA.toString())
        );
        registry.registerItem(legacy, invA);

        // Safe unscoped caller gets the item
        Optional<EvidenceItem> foundUnscoped = registry.findItemById(evdId);
        assertThat(foundUnscoped).isPresent();
        assertThat(foundUnscoped.get().evidenceId()).isEqualTo(evdId);

        // Scoped query for Inv B cannot see Inv A's item
        Optional<EvidenceItem> foundScopedB = registry.findItemById(evdId, invB);
        assertThat(foundScopedB).isEmpty();
    }

    @Test
    @DisplayName("Test F: Supporting and contradictory evidence independently scoped")
    void testF_supportingAndContradictoryEvidenceIndependentlyScoped() {
        String supId = "EVD-GIT-1234567";
        String contraId = "EVD-CONTRA-POST-1234567";

        EvidenceItem sup = EvidenceItem.create(supId, EvidenceType.COMMIT, "GIT", "1234567", Instant.now(), "Commit", Map.of("investigationId", invA.toString()));
        EvidenceItem contra = EvidenceItem.create(contraId, EvidenceType.COMMIT, "ANALYSIS", "1234567", Instant.now(), "Post-failure commit: zero database or stack trace overlap", Map.of("investigationId", invA.toString()));

        registry.registerItem(sup, invA);
        registry.registerItem(contra, invA);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-1234567",
                invA,
                "Commit 1234567 caused failure",
                List.of(supId),
                List.of(contraId)
        );

        InvestigationClaim validated = validator.validateClaim(claim, invA, registry);
        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.CONTRADICTED);
        assertThat(validated.explanation()).contains("refuted by contradictory evidence");
    }

    @Test
    @DisplayName("Test G: Cold PostgreSQL rehydration preserves ownership")
    void testG_coldPostgreSqlRehydrationPreservesOwnership() {
        String evdId = "EVD-GIT-cold123";
        EvidenceItemEntity entity = new EvidenceItemEntity(
                evdId,
                invA,
                "COMMIT",
                "GIT",
                "cold123",
                "Cold rehydrated commit",
                "{\"investigationId\":\"" + invA + "\",\"author\":\"Charlie\"}",
                null
        );
        String raw = evdId + "|GIT|cold123|Cold rehydrated commit";
        String hash = EvidenceItem.computeFullSha256(raw);
        entity.setIntegrityHash(hash);

        when(mockRepo.findByInvestigationIdAndEvidenceId(eq(invA), eq(evdId)))
                .thenReturn(Optional.of(entity));

        Optional<EvidenceItem> rehydratedA = registry.findItemById(evdId, invA);
        assertThat(rehydratedA).isPresent();
        assertThat(rehydratedA.get().evidenceId()).isEqualTo(evdId);
        assertThat(rehydratedA.get().metadata().get("investigationId")).isEqualTo(invA.toString());

        // For invB, mock repo returns empty
        when(mockRepo.findByInvestigationIdAndEvidenceId(eq(invB), eq(evdId)))
                .thenReturn(Optional.empty());
        Optional<EvidenceItem> rehydratedB = registry.findItemById(evdId, invB);
        assertThat(rehydratedB).isEmpty();
    }

    @Test
    @DisplayName("Test H: Historical TacitIQ evidence and claims remain resolvable")
    void testH_historicalTacitIqEvidenceAndClaimsRemainResolvable() {
        UUID tacitiqInv = UUID.fromString("5c48eb24-a00e-435a-aff8-88879ea113fb");
        String gitEvd = "EVD-GIT-52f53d7";
        String summary = "TacitIQ historical commit";
        String raw = gitEvd + "|GIT|52f53d7|" + summary;
        String hash = EvidenceItem.computeFullSha256(raw);

        EvidenceItemEntity entity = new EvidenceItemEntity(
                gitEvd,
                tacitiqInv,
                "COMMIT",
                "GIT",
                "52f53d7",
                summary,
                "{\"investigationId\":\"" + tacitiqInv + "\"}",
                hash
        );

        when(mockRepo.findByInvestigationIdAndEvidenceId(eq(tacitiqInv), eq(gitEvd)))
                .thenReturn(Optional.of(entity));

        Optional<EvidenceItem> historical = registry.findItemById(gitEvd, tacitiqInv);
        assertThat(historical).isPresent();
        assertThat(historical.get().evidenceId()).isEqualTo(gitEvd);

        InvestigationClaim claim = InvestigationClaim.create(
                "CLM-52f53d7",
                tacitiqInv,
                "Historical commit 52f53d7 evaluated",
                List.of(gitEvd),
                Collections.emptyList()
        );

        InvestigationClaim validated = validator.validateClaim(claim, tacitiqInv, registry);
        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
    }

    @Test
    @DisplayName("Test I: Concurrent registration of same evidence ID in two investigations does not cause contamination")
    void testI_concurrentRegistrationNoContamination() throws Exception {
        String sharedId = "EVD-GIT-concurrent";
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            final UUID inv = (idx % 2 == 0) ? invA : invB;
            final String author = (idx % 2 == 0) ? "AuthorA" : "AuthorB";

            pool.submit(() -> {
                try {
                    latch.await();
                    EvidenceItem item = EvidenceItem.create(
                            sharedId,
                            EvidenceType.COMMIT,
                            "GIT",
                            "sha-" + idx,
                            Instant.now(),
                            "Commit " + idx,
                            Map.of("author", author, "investigationId", inv.toString())
                    );
                    registry.registerItem(item, inv);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        latch.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        Optional<EvidenceItem> itemA = registry.findItemById(sharedId, invA);
        Optional<EvidenceItem> itemB = registry.findItemById(sharedId, invB);

        assertThat(itemA).isPresent();
        assertThat(itemA.get().metadata().get("author")).isEqualTo("AuthorA");

        assertThat(itemB).isPresent();
        assertThat(itemB.get().metadata().get("author")).isEqualTo("AuthorB");
    }

    @Test
    @DisplayName("Test J: Ambiguous legacy IDs never resolve silently to another investigation")
    void testJ_ambiguousLegacyIdsNeverResolveSilentlyToAnotherInvestigation() {
        String ambiguousId = "EVD-GIT-ambiguous";

        // Registered ONLY under Inv B
        EvidenceItem itemB = EvidenceItem.create(
                ambiguousId,
                EvidenceType.COMMIT,
                "GIT",
                "sha-b",
                Instant.now(),
                "Commit B only",
                Map.of("investigationId", invB.toString())
        );
        registry.registerItem(itemB, invB);

        // Investigation A queries for ambiguousId with its scoped method
        Optional<EvidenceItem> scopedQueryA = registry.findItemById(ambiguousId, invA);
        assertThat(scopedQueryA).isEmpty();

        // Validation of claim under Inv A referencing ambiguousId must be UNSUPPORTED, never silently accepted
        InvestigationClaim claimA = InvestigationClaim.create(
                "CLM-AMB-01",
                invA,
                "Inv A asserts ambiguous commit",
                List.of(ambiguousId),
                Collections.emptyList()
        );

        InvestigationClaim validated = validator.validateClaim(claimA, invA, registry);
        assertThat(validated.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
        assertThat(validated.explanation()).contains("belongs to a different investigation (" + invB + ")");
    }

    @Test
    @DisplayName("Scenario A Exact Forensic Revalidation: All 3 claims validate as SUPPORTED against Scenario A evidence")
    void testScenarioA_ExactForensicRevalidation() {
        UUID scenarioAId = UUID.fromString("9cd50b74-ee3f-451b-b1ea-837b4f6ce195");

        EvidenceItem evdGit12 = EvidenceItem.create(
                "EVD-GIT-12ef988",
                EvidenceType.COMMIT,
                "GIT",
                "12ef988366e9d16ca2a1c8ae7174ad7f4866d976",
                Instant.parse("2026-11-08T17:02:41.235Z"),
                "feat: initial commit with standard Hikari connection pool configuration",
                Map.of("investigationId", scenarioAId.toString())
        );

        EvidenceItem evdFrame12 = EvidenceItem.create(
                "EVD-FRAME-12ef988-OrderService.java",
                EvidenceType.STACK_FRAME,
                "STACK_TRACE",
                "OrderService.java:35",
                Instant.parse("2026-11-08T17:02:41.235Z"),
                "Stack frame com.example.orders.OrderService.createOrder matches changed file",
                Map.of("investigationId", scenarioAId.toString())
        );

        EvidenceItem evdGitEe = EvidenceItem.create(
                "EVD-GIT-ee51c22",
                EvidenceType.COMMIT,
                "GIT",
                "ee51c220dc3c17a81bd099065d30405ffa996859",
                Instant.parse("2026-11-08T17:02:41.235Z"),
                "perf(db): optimize connection pool parameters for low memory",
                Map.of("investigationId", scenarioAId.toString())
        );

        EvidenceItem evdCfg12 = EvidenceItem.create(
                "EVD-CFG-12ef988",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "12ef988:config",
                Instant.parse("2026-11-08T17:02:41.235Z"),
                "Configuration delta in commit 12ef988: [src/main/resources/application.yml] ([spring, connection-timeout, server, port, datasource, minimum-idle, maximum-pool-size, hikari])",
                Map.of("investigationId", scenarioAId.toString(), "key", "timeout")
        );

        EvidenceItem evdCfgEe = EvidenceItem.create(
                "EVD-CFG-ee51c22",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "ee51c22:config",
                Instant.parse("2026-11-08T17:02:41.235Z"),
                "Configuration delta in commit ee51c22: [src/main/resources/application.yml] ([connection-timeout, minimum-idle, maximum-pool-size])",
                Map.of("investigationId", scenarioAId.toString(), "key", "timeout")
        );

        registry.registerItem(evdGit12, scenarioAId);
        registry.registerItem(evdFrame12, scenarioAId);
        registry.registerItem(evdGitEe, scenarioAId);
        registry.registerItem(evdCfg12, scenarioAId);
        registry.registerItem(evdCfgEe, scenarioAId);

        // Claim 1: CLM-12ef988
        InvestigationClaim clm12 = InvestigationClaim.create(
                "CLM-12ef988",
                scenarioAId,
                "Potential contributing change in commit 12ef988 (\"feat: initial commit with standard Hikari connection pool configuration\"): modified configuration settings (spring, connection-timeout, server, port, datasource, minimum-idle, maximum-pool-size, hikari) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.",
                List.of("EVD-GIT-12ef988", "EVD-FRAME-12ef988-OrderService.java", "EVD-CFG-12ef988"),
                Collections.emptyList()
        );
        InvestigationClaim validatedClm12 = validator.validateClaim(clm12, scenarioAId, registry);
        assertThat(validatedClm12.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validatedClm12.explanation()).contains("Supported by 3 verified evidence item(s)");

        // Claim 2: CLM-ee51c22
        InvestigationClaim clmEe = InvestigationClaim.create(
                "CLM-ee51c22",
                scenarioAId,
                "Potential contributing change in commit ee51c22 (\"perf(db): optimize connection pool parameters for low memory\"): modified configuration settings (connection-timeout, minimum-idle, maximum-pool-size) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.",
                List.of("EVD-GIT-ee51c22", "EVD-CFG-ee51c22"),
                Collections.emptyList()
        );
        InvestigationClaim validatedClmEe = validator.validateClaim(clmEe, scenarioAId, registry);
        assertThat(validatedClmEe.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validatedClmEe.explanation()).contains("Supported by 2 verified evidence item(s)");

        // Claim 3: CLAIM-AGENT-PRIMARY-9cd50b74
        InvestigationClaim clmPrimary = InvestigationClaim.create(
                "CLAIM-AGENT-PRIMARY-9cd50b74",
                scenarioAId,
                "Commit ee51c220dc3c17a81bd099065d30405ffa996859 is a probable contributing change for order-service failure.",
                List.of("EVD-GIT-ee51c22", "EVD-CFG-ee51c22"),
                Collections.emptyList()
        );
        InvestigationClaim validatedPrimary = validator.validateClaim(clmPrimary, scenarioAId, registry);
        assertThat(validatedPrimary.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(validatedPrimary.explanation()).contains("Supported by 2 verified evidence item(s)");
    }

    @Test
    @DisplayName("Scenario B Exact Forensic Revalidation: All claims validate against Scenario B scoped evidence")
    void testScenarioB_ExactForensicRevalidation() {
        UUID scenarioBId = UUID.fromString("3bc02925-2215-4d9f-a1bf-19b56e139a98");

        EvidenceItem evdGit9f = EvidenceItem.create(
                "EVD-GIT-9fb2786",
                EvidenceType.COMMIT,
                "GIT",
                "9fb27863cfc1c773a92c11a7a025a45f9d409ccc",
                Instant.now(),
                "feat(users): add user profile endpoint with nested role access",
                Map.of("investigationId", scenarioBId.toString())
        );

        EvidenceItem evdFrame9f = EvidenceItem.create(
                "EVD-FRAME-9fb2786-users.js",
                EvidenceType.STACK_FRAME,
                "GIT",
                "users.js:18",
                Instant.now(),
                "Stack frame router.get matches changed file",
                Map.of("investigationId", scenarioBId.toString())
        );

        EvidenceItem evdGit2a = EvidenceItem.create(
                "EVD-GIT-2a28b00",
                EvidenceType.COMMIT,
                "GIT",
                "2a28b0060a5774f34c11e591104862904e1ed23b",
                Instant.now(),
                "feat: initial express user service",
                Map.of("investigationId", scenarioBId.toString())
        );

        EvidenceItem evdFrame2a = EvidenceItem.create(
                "EVD-FRAME-2a28b00-users.js",
                EvidenceType.STACK_FRAME,
                "GIT",
                "users.js:18",
                Instant.now(),
                "Stack frame router.get matches changed file",
                Map.of("investigationId", scenarioBId.toString())
        );

        registry.registerItem(evdGit9f, scenarioBId);
        registry.registerItem(evdFrame9f, scenarioBId);
        registry.registerItem(evdGit2a, scenarioBId);
        registry.registerItem(evdFrame2a, scenarioBId);

        InvestigationClaim clm9f = InvestigationClaim.create(
                "CLM-9fb2786",
                scenarioBId,
                "Code regression in commit 9fb2786 (\"feat(users): add user profile endpoint with nested role access\"): modified file matches runtime crash stack frame. Available evidence supports code correlation, but does not independently establish active deployment timing.",
                List.of("EVD-GIT-9fb2786", "EVD-FRAME-9fb2786-users.js"),
                Collections.emptyList()
        );
        InvestigationClaim validatedClm9f = validator.validateClaim(clm9f, scenarioBId, registry);
        assertThat(validatedClm9f.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);

        InvestigationClaim clm2a = InvestigationClaim.create(
                "CLM-2a28b00",
                scenarioBId,
                "Code regression in commit 2a28b00 (\"feat: initial express user service\"): modified file matches runtime crash stack frame. Available evidence supports code correlation, but does not independently establish active deployment timing.",
                List.of("EVD-GIT-2a28b00", "EVD-FRAME-2a28b00-users.js"),
                Collections.emptyList()
        );
        InvestigationClaim validatedClm2a = validator.validateClaim(clm2a, scenarioBId, registry);
        assertThat(validatedClm2a.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);

        InvestigationClaim clmPrimary = InvestigationClaim.create(
                "CLAIM-AGENT-PRIMARY-3bc02925",
                scenarioBId,
                "Commit 9fb27863cfc1c773a92c11a7a025a45f9d409ccc is a probable contributing change for user-service failure.",
                List.of("EVD-GIT-9fb2786", "EVD-FRAME-9fb2786-users.js"),
                Collections.emptyList()
        );
        InvestigationClaim validatedPrimary = validator.validateClaim(clmPrimary, scenarioBId, registry);
        assertThat(validatedPrimary.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
    }

    @Test
    @DisplayName("Scenario C Exact Forensic Revalidation: Working tree anomaly claims validate as SUPPORTED")
    void testScenarioC_ExactForensicRevalidation() {
        UUID scenarioCId = UUID.fromString("74880eda-145c-4ebb-bdd2-630233cc87a5");

        EvidenceItem evdMissing1 = EvidenceItem.create(
                "EVD-WT-MISSING-587cb980af",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "src/auth/jwtValidator.ts",
                Instant.now(),
                "Tracked file missing from disk: src/auth/jwtValidator.ts (present in HEAD 25009aa)",
                Map.of("investigationId", scenarioCId.toString())
        );

        EvidenceItem evdMissing2 = EvidenceItem.create(
                "EVD-WT-MISSING-d168d1ee3d",
                EvidenceType.WORKING_TREE_CHANGE,
                "WORKING_TREE",
                "src/config/security.ts",
                Instant.now(),
                "Tracked file missing from disk: src/config/security.ts (present in HEAD 25009aa)",
                Map.of("investigationId", scenarioCId.toString())
        );

        registry.registerItem(evdMissing1, scenarioCId);
        registry.registerItem(evdMissing2, scenarioCId);

        InvestigationClaim clm1 = InvestigationClaim.create(
                "CLM-WT-MISSING-587cb980af",
                scenarioCId,
                "Working tree anomaly: tracked file 'src/auth/jwtValidator.ts' is missing from disk but exists in HEAD commit 25009aa.",
                List.of("EVD-WT-MISSING-587cb980af"),
                Collections.emptyList()
        );
        InvestigationClaim val1 = validator.validateClaim(clm1, scenarioCId, registry);
        assertThat(val1.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);

        InvestigationClaim clm2 = InvestigationClaim.create(
                "CLM-WT-MISSING-d168d1ee3d",
                scenarioCId,
                "Working tree anomaly: tracked file 'src/config/security.ts' is missing from disk but exists in HEAD commit 25009aa.",
                List.of("EVD-WT-MISSING-d168d1ee3d"),
                Collections.emptyList()
        );
        InvestigationClaim val2 = validator.validateClaim(clm2, scenarioCId, registry);
        assertThat(val2.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
    }

    @Test
    @DisplayName("Scenario D Exact Forensic Revalidation: Cosmetic documentation commits are CONTRADICTED")
    void testScenarioD_ExactForensicRevalidation() {
        UUID scenarioDId = UUID.fromString("59bbecc4-41d4-49ef-8b1b-07c34d5153d3");

        EvidenceItem evdGit8a = EvidenceItem.create(
                "EVD-GIT-8a7ec51",
                EvidenceType.COMMIT,
                "GIT",
                "8a7ec51684c312fbfe7475f7ba14246067b54ab9",
                Instant.now(),
                "docs: initial architectural documentation",
                Map.of("investigationId", scenarioDId.toString())
        );

        EvidenceItem evdContra8a = EvidenceItem.create(
                "EVD-CONTRA-COSMETIC-8a7ec51",
                EvidenceType.COMMIT,
                "GIT",
                "8a7ec51:cosmetic",
                Instant.now(),
                "Cosmetic commit: Commit modified only cosmetic or documentation files; zero functional code or configuration impact",
                Map.of("investigationId", scenarioDId.toString())
        );

        registry.registerItem(evdGit8a, scenarioDId);
        registry.registerItem(evdContra8a, scenarioDId);

        InvestigationClaim clm8a = InvestigationClaim.create(
                "CLM-8a7ec51",
                scenarioDId,
                "Candidate commit 8a7ec51 (\"docs: initial architectural documentation\") shows weak correlation (heuristic confidence: 6%).",
                List.of("EVD-GIT-8a7ec51"),
                List.of("EVD-CONTRA-COSMETIC-8a7ec51")
        );
        InvestigationClaim val8a = validator.validateClaim(clm8a, scenarioDId, registry);
        assertThat(val8a.status()).isEqualTo(ClaimValidationStatus.CONTRADICTED);
        assertThat(val8a.explanation()).contains("refuted by contradictory evidence");
    }

    @Test
    @DisplayName("Scenario E Exact Forensic Revalidation: Config regression claims are SUPPORTED")
    void testScenarioE_ExactForensicRevalidation() {
        UUID scenarioEId = UUID.fromString("c53c484c-8c43-4bf9-a631-62e60bd28ff5");

        EvidenceItem evdGitC8 = EvidenceItem.create(
                "EVD-GIT-c881289",
                EvidenceType.COMMIT,
                "GIT",
                "c8812895518b2f5784bd2a43dc47a300e3092ee5",
                Instant.now(),
                "refactor(config): severely restrict connection pool size and timeout",
                Map.of("investigationId", scenarioEId.toString())
        );

        EvidenceItem evdCfgC8 = EvidenceItem.create(
                "EVD-CFG-c881289",
                EvidenceType.CONFIG_DIFF,
                "GIT",
                "c881289:config",
                Instant.now(),
                "Configuration delta in commit c881289: [src/main/resources/application.properties] ([spring.datasource.hikari.connection-timeout, spring.datasource.hikari.maximum-pool-size])",
                Map.of("investigationId", scenarioEId.toString(), "key", "pool")
        );

        registry.registerItem(evdGitC8, scenarioEId);
        registry.registerItem(evdCfgC8, scenarioEId);

        InvestigationClaim clmC8 = InvestigationClaim.create(
                "CLM-c881289",
                scenarioEId,
                "Potential contributing change in commit c881289 (\"refactor(config): severely restrict connection pool size and timeout\"): modified configuration settings (spring.datasource.hikari.maximum-pool-size, spring.datasource.hikari.connection-timeout) aligning with runtime failure symptom (FATAL: remaining connection slots are reserved). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.",
                List.of("EVD-GIT-c881289", "EVD-CFG-c881289"),
                Collections.emptyList()
        );
        InvestigationClaim valC8 = validator.validateClaim(clmC8, scenarioEId, registry);
        assertThat(valC8.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
        assertThat(valC8.explanation()).contains("Supported by 2 verified evidence item(s)");
    }
}
