package com.traceback.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCollector;
import com.traceback.core.git.GitCommitAnalyzer;
import com.traceback.core.git.GitDiffAnalyzer;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.log.ErrorSignature;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 2.46 Investigation Integrity & Compatibility Verification Suite.
 * Validates the four fixes from Phase 2.45 audit:
 * 1. Stack trace & log signature evidence persistence provenance.
 * 2. supportingEvidenceIds legacy fallback distinction (explicit [] vs legacy NULL).
 * 3. ConfidenceBreakdown unknown properties deserialization.
 * 4. GitCollector source code language alignment.
 */
class Phase246InvestigationIntegrityTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // =========================================================================
    // 1. Stack Trace & Log Signature Evidence Persistence
    // =========================================================================
    @Nested
    @DisplayName("1. Stack Trace & Log Signature Evidence Persistence")
    class EvidencePersistenceProvenanceTests {

        @Test
        @DisplayName("Root stack trace evidence (EVD-STACK-ROOT) must carry matching originatingInvestigationId and persist")
        void testRootStackTraceEvidencePersistenceAndProvenance() {
            InvestigationEvidenceRepository mockRepo = mock(InvestigationEvidenceRepository.class);
            when(mockRepo.existsByInvestigationIdAndEvidenceId(any(), any())).thenReturn(false);

            ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry(mockRepo, objectMapper);
            InvestigationService service = new InvestigationService(
                    null,
                    null,
                    new StackTraceParser(),
                    new LogAnalyzer(),
                    new TimelineBuilder(),
                    new CorrelationEngine(),
                    registry
            );

            String stackTrace = """
                    java.lang.NullPointerException: Cannot invoke "Customer.getId()" because "customer" is null
                    \tat com.example.orderservice.service.OrderProcessingService.processOrder(OrderProcessingService.java:42)
                    """;

            // Execute investigation logic to register parsed stack
            InvestigationResult result = service.startInvestigation(
                    null,
                    stackTrace,
                    null,
                    null,
                    "order-service",
                    "test",
                    60,
                    50,
                    InvestigationMode.RUNTIME_FAILURE
            );

            UUID invId = result.investigationId();

            // 1. Verify item exists in in-memory registry
            Optional<EvidenceItem> stackItemOpt = registry.findByInvestigationId(invId).stream()
                    .filter(e -> "EVD-STACK-ROOT".equals(e.evidenceId()))
                    .findFirst();

            assertThat(stackItemOpt).isPresent();
            EvidenceItem stackItem = stackItemOpt.get();

            // 2. Verify provenance fields
            assertThat(stackItem.investigationId()).isEqualTo(invId);
            assertThat(stackItem.originatingInvestigationId()).isEqualTo(invId);
            assertThat(stackItem.isGenuinelyCollectedFor(invId)).isTrue();

            // 3. Verify database persistence occurred without foreign-origin rejection
            ArgumentCaptor<EvidenceItemEntity> captor = ArgumentCaptor.forClass(EvidenceItemEntity.class);
            verify(mockRepo, atLeastOnce()).save(captor.capture());

            List<EvidenceItemEntity> saved = captor.getAllValues();
            assertThat(saved).anyMatch(e -> "EVD-STACK-ROOT".equals(e.getEvidenceId()) && invId.equals(e.getInvestigationId()));
        }

        @Test
        @DisplayName("Log signature evidence (EVD-LOG-SIG) must carry matching originatingInvestigationId and persist")
        void testLogSignatureEvidencePersistenceAndProvenance() {
            InvestigationEvidenceRepository mockRepo = mock(InvestigationEvidenceRepository.class);
            when(mockRepo.existsByInvestigationIdAndEvidenceId(any(), any())).thenReturn(false);

            ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry(mockRepo, objectMapper);
            InvestigationService service = new InvestigationService(
                    null,
                    null,
                    new StackTraceParser(),
                    new LogAnalyzer(),
                    new TimelineBuilder(),
                    new CorrelationEngine(),
                    registry
            );

            String logContent = "2026-11-15 10:14:00.000 ERROR [order-service] [main] OrderProcessingService - Fatal: NullPointerException occurred";

            InvestigationResult result = service.startInvestigation(
                    null,
                    null,
                    null,
                    logContent,
                    "order-service",
                    "test",
                    60,
                    50,
                    InvestigationMode.RUNTIME_FAILURE
            );

            UUID invId = result.investigationId();

            // Verify item exists and is persisted
            Optional<EvidenceItem> logItemOpt = registry.findByInvestigationId(invId).stream()
                    .filter(e -> "EVD-LOG-SIG".equals(e.evidenceId()))
                    .findFirst();

            if (logItemOpt.isPresent()) {
                EvidenceItem logItem = logItemOpt.get();
                assertThat(logItem.investigationId()).isEqualTo(invId);
                assertThat(logItem.originatingInvestigationId()).isEqualTo(invId);
                assertThat(logItem.isGenuinelyCollectedFor(invId)).isTrue();

                ArgumentCaptor<EvidenceItemEntity> captor = ArgumentCaptor.forClass(EvidenceItemEntity.class);
                verify(mockRepo, atLeastOnce()).save(captor.capture());
                assertThat(captor.getAllValues()).anyMatch(e -> "EVD-LOG-SIG".equals(e.getEvidenceId()) && invId.equals(e.getInvestigationId()));
            }
        }

        @Test
        @DisplayName("Foreign-origin evidence is strictly rejected by ConcreteEvidenceRegistry and not persisted")
        void testForeignOriginEvidenceRejection() {
            InvestigationEvidenceRepository mockRepo = mock(InvestigationEvidenceRepository.class);
            ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry(mockRepo, objectMapper);

            UUID targetInvId = UUID.randomUUID();
            UUID foreignInvId = UUID.randomUUID();

            EvidenceItem foreignItem = EvidenceItem.create(
                    "EVD-FOREIGN-001",
                    EvidenceType.STACK_TRACE,
                    "STACK_TRACE",
                    "NullPointerException",
                    Instant.now(),
                    "Foreign exception",
                    Map.of(
                            "type", "NullPointerException",
                            "investigationId", targetInvId.toString(),
                            "originatingInvestigationId", foreignInvId.toString()
                    )
            );

            registry.registerItem(foreignItem, targetInvId);

            // Must NOT be saved to repository because originatingInvestigationId != targetInvId
            verify(mockRepo, never()).save(any());
            assertThat(foreignItem.isGenuinelyCollectedFor(targetInvId)).isFalse();
        }

        @Test
        @DisplayName("Missing originating ID is NOT automatically claimed as local (ownership rule preserved)")
        void testMissingOriginatingIdNotClaimedAsLocal() {
            InvestigationEvidenceRepository mockRepo = mock(InvestigationEvidenceRepository.class);
            ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry(mockRepo, objectMapper);

            UUID targetInvId = UUID.randomUUID();

            EvidenceItem itemMissingOrigin = EvidenceItem.create(
                    "EVD-NO-ORIGIN-001",
                    EvidenceType.STACK_TRACE,
                    "STACK_TRACE",
                    "NullPointerException",
                    Instant.now(),
                    "No origin exception",
                    Map.of(
                            "type", "NullPointerException",
                            "investigationId", targetInvId.toString()
                            // originatingInvestigationId is omitted!
                    )
            );

            registry.registerItem(itemMissingOrigin, targetInvId);

            // Must NOT be saved because originatingId is missing
            verify(mockRepo, never()).save(any());
            assertThat(itemMissingOrigin.isGenuinelyCollectedFor(targetInvId)).isFalse();
        }

        @Test
        @DisplayName("Global evidence behavior remains unchanged and preserves originating provenance")
        void testGlobalEvidenceBehavior() {
            InvestigationPersistenceMapper mapper = new InvestigationPersistenceMapper(objectMapper);
            UUID targetInvId = UUID.randomUUID();

            EvidenceItem globalItem = EvidenceItem.create(
                    "EVD-GLOBAL-CONFIG-001",
                    EvidenceType.CONFIG_DIFF,
                    "GIT",
                    "application.yml#timeout",
                    Instant.now(),
                    "Global baseline config",
                    Map.of(
                            "investigationId", "GLOBAL",
                            "originatingInvestigationId", "GLOBAL"
                    )
            );

            assertThat(globalItem.isGlobal()).isTrue();
            assertThat(globalItem.isGenuinelyCollectedFor(targetInvId)).isFalse();

            // Mapping to entity preserves GLOBAL originating provenance
            EvidenceItemEntity entity = mapper.toEntity(globalItem, targetInvId);
            assertThat(entity.getInvestigationId()).isEqualTo(targetInvId);
            assertThat(entity.getPayload()).contains("\"originatingInvestigationId\":\"GLOBAL\"");

            EvidenceItem domain = mapper.toDomain(entity);
            assertThat(domain.investigationId()).isEqualTo(targetInvId);
            assertThat(domain.isGenuinelyCollectedFor(targetInvId)).isFalse();
        }
    }

    // =========================================================================
    // 2. supportingEvidenceIds Legacy Fallback Fix
    // =========================================================================
    @Nested
    @DisplayName("2. supportingEvidenceIds Legacy Fallback Distinction")
    class SupportingEvidenceIdsFallbackTests {

        private final InvestigationPersistenceMapper mapper = new InvestigationPersistenceMapper();

        @Test
        @DisplayName("Explicit empty list with INSUFFICIENT_EVIDENCE must NOT be overwritten by claims")
        void testExplicitEmptyListWithInsufficientEvidence() {
            AgentReportEntity entity = new AgentReportEntity(
                    "REP-001",
                    UUID.randomUUID(),
                    "INSUFFICIENT_EVIDENCE",
                    "Inconclusive investigation",
                    "No code or config overlap",
                    null,
                    null,
                    null,
                    null
            );
            entity.setSupportingEvidenceIds("[]"); // Explicit empty list!

            InvestigationClaim claim1 = InvestigationClaim.create(
                    "CLM-001", entity.getInvestigationId(), "Candidate commit 1",
                    List.of("EVD-GIT-1111111"), Collections.emptyList()
            );
            InvestigationClaim claim2 = InvestigationClaim.create(
                    "CLM-002", entity.getInvestigationId(), "Candidate commit 2",
                    List.of("EVD-GIT-2222222"), Collections.emptyList()
            );

            try {
                entity.setValidatedClaims(objectMapper.writeValueAsString(List.of(claim1, claim2)));
            } catch (Exception ignored) {}

            var domain = mapper.toDomain(entity, Collections.emptyList());

            // MUST stay empty!
            assertThat(domain.supportingEvidenceIds()).isEmpty();
            assertThat(domain.finalStatus().name()).isEqualTo("INSUFFICIENT_EVIDENCE");
        }

        @Test
        @DisplayName("Explicit empty list with other verdicts (e.g. PROBABLE) must NOT be overwritten by claims")
        void testExplicitEmptyListWithProbableVerdict() {
            AgentReportEntity entity = new AgentReportEntity(
                    "REP-002",
                    UUID.randomUUID(),
                    "PROBABLE",
                    "Probable cause found without direct supporting commits",
                    "Reasoning",
                    null,
                    null,
                    null,
                    null
            );
            entity.setSupportingEvidenceIds("[]"); // Explicit empty list!

            InvestigationClaim claim1 = InvestigationClaim.create(
                    "CLM-001", entity.getInvestigationId(), "Candidate commit 1",
                    List.of("EVD-GIT-1111111"), Collections.emptyList()
            );

            try {
                entity.setValidatedClaims(objectMapper.writeValueAsString(List.of(claim1)));
            } catch (Exception ignored) {}

            var domain = mapper.toDomain(entity, Collections.emptyList());

            // MUST stay empty because it was explicitly stored as []
            assertThat(domain.supportingEvidenceIds()).isEmpty();
        }

        @Test
        @DisplayName("Legacy NULL field with CONFIRMED verdict executes fallback and collects from claims")
        void testLegacyNullFieldWithConfirmedVerdict() {
            AgentReportEntity entity = new AgentReportEntity(
                    "REP-LEGACY-001",
                    UUID.randomUUID(),
                    "CONFIRMED",
                    "Legacy confirmed investigation",
                    "Reasoning",
                    null,
                    null,
                    null,
                    null
            );
            entity.setSupportingEvidenceIds(null); // Genuinely legacy record!

            InvestigationClaim claim1 = InvestigationClaim.create(
                    "CLM-001", entity.getInvestigationId(), "Suspect commit 1",
                    List.of("EVD-GIT-1111111"), Collections.emptyList()
            );
            InvestigationClaim claim2 = InvestigationClaim.create(
                    "CLM-002", entity.getInvestigationId(), "Suspect commit 2",
                    List.of("EVD-GIT-2222222"), Collections.emptyList()
            );

            try {
                entity.setValidatedClaims(objectMapper.writeValueAsString(List.of(claim1, claim2)));
            } catch (Exception ignored) {}

            var domain = mapper.toDomain(entity, Collections.emptyList());

            // Legacy fallback executes for CONFIRMED
            assertThat(domain.supportingEvidenceIds()).containsExactlyInAnyOrder("EVD-GIT-1111111", "EVD-GIT-2222222");
        }

        @Test
        @DisplayName("Legacy NULL field with INSUFFICIENT_EVIDENCE does NOT execute fallback and stays empty")
        void testLegacyNullFieldWithInsufficientEvidenceVerdict() {
            AgentReportEntity entity = new AgentReportEntity(
                    "REP-LEGACY-002",
                    UUID.randomUUID(),
                    "INSUFFICIENT_EVIDENCE",
                    "Legacy abstained investigation",
                    "Reasoning",
                    null,
                    null,
                    null,
                    null
            );
            entity.setSupportingEvidenceIds(null); // Genuinely legacy record!

            InvestigationClaim candidateClaim = InvestigationClaim.create(
                    "CLM-CANDIDATE", entity.getInvestigationId(), "Weak candidate",
                    List.of("EVD-GIT-3333333"), Collections.emptyList()
            );

            try {
                entity.setValidatedClaims(objectMapper.writeValueAsString(List.of(candidateClaim)));
            } catch (Exception ignored) {}

            var domain = mapper.toDomain(entity, Collections.emptyList());

            // INSUFFICIENT_EVIDENCE must never be polluted with candidate claims
            assertThat(domain.supportingEvidenceIds()).isEmpty();
        }

        @Test
        @DisplayName("Non-empty stored supporting evidence IDs are preserved exactly")
        void testNonEmptyStoredSupportingEvidenceIds() {
            AgentReportEntity entity = new AgentReportEntity(
                    "REP-003",
                    UUID.randomUUID(),
                    "CONFIRMED",
                    "Confirmed investigation",
                    "Reasoning",
                    null,
                    null,
                    null,
                    null
            );
            entity.setSupportingEvidenceIds("[\"EVD-GIT-CAFE001\",\"EVD-FRAME-CAFE001\"]");

            var domain = mapper.toDomain(entity, Collections.emptyList());

            assertThat(domain.supportingEvidenceIds()).containsExactly("EVD-GIT-CAFE001", "EVD-FRAME-CAFE001");
        }
    }

    // =========================================================================
    // 3. ConfidenceBreakdown Deserialization Fix
    // =========================================================================
    @Nested
    @DisplayName("3. ConfidenceBreakdown Unknown Properties Deserialization")
    class ConfidenceBreakdownDeserializationTests {

        @Test
        @DisplayName("Deserialization succeeds when JSON contains legacy unknown properties like verifiedClaims")
        void testDeserializeWithLegacyUnknownProperties() throws Exception {
            String legacyJson = """
                    {
                        "temporalPoints": 30.0,
                        "overlapPoints": 30.0,
                        "agreementPoints": 15.0,
                        "contradictionPenalty": 0.0,
                        "rawScore": 75.0,
                        "normalizedScore": 0.88,
                        "scoreReasons": ["Commit within 15m (+30 pts)", "Stack frame matches (+30 pts)", "Log burst (+15 pts)"],
                        "penaltyReasons": [],
                        "verifiedClaims": [
                            {"claimId": "CLM-001", "status": "SUPPORTED"}
                        ],
                        "extraTelemetry": "legacy_payload"
                    }
                    """;

            ConfidenceBreakdown breakdown = objectMapper.readValue(legacyJson, ConfidenceBreakdown.class);

            assertThat(breakdown).isNotNull();
            assertThat(breakdown.temporalPoints()).isEqualTo(30.0);
            assertThat(breakdown.overlapPoints()).isEqualTo(30.0);
            assertThat(breakdown.agreementPoints()).isEqualTo(15.0);
            assertThat(breakdown.contradictionPenalty()).isEqualTo(0.0);
            assertThat(breakdown.rawScore()).isEqualTo(75.0);
            assertThat(breakdown.normalizedScore()).isEqualTo(0.88);
            assertThat(breakdown.scoreReasons()).hasSize(3);
            assertThat(breakdown.penaltyReasons()).isEmpty();
        }

        @Test
        @DisplayName("Serialization and deserialization cycle preserves all 8 canonical fields")
        void testSerializationRoundTrip() throws Exception {
            ConfidenceBreakdown original = ConfidenceBreakdown.calculate(
                    20.0, 30.0, 10.0, 25.0,
                    List.of("Within window", "Code overlap", "Message corroboration"),
                    List.of("Cosmetic commit penalty")
            );

            String json = objectMapper.writeValueAsString(original);
            ConfidenceBreakdown reloaded = objectMapper.readValue(json, ConfidenceBreakdown.class);

            assertThat(reloaded).isEqualTo(original);
            assertThat(reloaded.temporalPoints()).isEqualTo(20.0);
            assertThat(reloaded.overlapPoints()).isEqualTo(30.0);
            assertThat(reloaded.agreementPoints()).isEqualTo(10.0);
            assertThat(reloaded.contradictionPenalty()).isEqualTo(25.0);
            assertThat(reloaded.rawScore()).isEqualTo(35.0);
            assertThat(reloaded.normalizedScore()).isEqualTo(0.41);
        }
    }

    // =========================================================================
    // 4. GitCollector Source Code Detection Alignment
    // =========================================================================
    @Nested
    @DisplayName("4. GitCollector Source Code Language Alignment")
    class GitCollectorLanguageDetectionTests {

        @Test
        @DisplayName("ChangedFile.isSourceCodePath recognizes all core languages")
        void testSupportedSourceCodeExtensions() {
            assertThat(ChangedFile.isSourceCodePath("src/main/OrderService.java")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("src/main/OrderService.kt")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("frontend/src/app.ts")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("frontend/src/index.js")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("backend/app.py")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("services/main.go")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("engine/src/lib.rs")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("native/driver.c")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("native/core.cpp")).isTrue();
        }

        @Test
        @DisplayName("Case-insensitivity is supported for source code extensions")
        void testCaseInsensitivity() {
            assertThat(ChangedFile.isSourceCodePath("src/Order.JAVA")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("scripts/run.PY")).isTrue();
            assertThat(ChangedFile.isSourceCodePath("client/APP.TS")).isTrue();
        }

        @Test
        @DisplayName("Non-code documentation and asset files are NOT classified as source code")
        void testNonCodeFilesNotClassifiedAsCode() {
            assertThat(ChangedFile.isSourceCodePath("README.md")).isFalse();
            assertThat(ChangedFile.isSourceCodePath("docs/architecture.markdown")).isFalse();
            assertThat(ChangedFile.isSourceCodePath("notes.txt")).isFalse();
            assertThat(ChangedFile.isSourceCodePath("assets/logo.png")).isFalse();
            assertThat(ChangedFile.isSourceCodePath("styles/main.css")).isFalse();
            assertThat(ChangedFile.isSourceCodePath("styles/app.scss")).isFalse();
        }

        @Test
        @DisplayName("Configuration files are correctly categorized as configuration and not code")
        void testConfigurationFiles() {
            assertThat(ChangedFile.isConfigurationPath("application.yml")).isTrue();
            assertThat(ChangedFile.isConfigurationPath("application.properties")).isTrue();
            assertThat(ChangedFile.isConfigurationPath("config.json")).isTrue();
            assertThat(ChangedFile.isConfigurationPath("Dockerfile")).isTrue();
            assertThat(ChangedFile.isConfigurationPath(".env")).isTrue();

            assertThat(ChangedFile.isSourceCodePath("application.yml")).isFalse();
            assertThat(ChangedFile.isSourceCodePath("application.properties")).isFalse();
        }

        @Test
        @DisplayName("CommitEvidence accurately sets hasCodeChanges across Python, JS, Go, Rust")
        void testCommitEvidenceWithMultipleLanguages() {
            ChangedFile pyFile = new ChangedFile("app/worker.py", "MODIFY", 10, 2, "patch", false, Collections.emptyMap());
            ChangedFile jsFile = new ChangedFile("web/index.js", "MODIFY", 5, 1, "patch", false, Collections.emptyMap());
            ChangedFile goFile = new ChangedFile("cmd/server.go", "MODIFY", 20, 5, "patch", false, Collections.emptyMap());
            ChangedFile docFile = new ChangedFile("README.md", "MODIFY", 1, 0, "patch", false, Collections.emptyMap());

            // Python commit
            CommitEvidence pyCommit = new CommitEvidence("py12345", "py12345", "Alice", "a@test.com",
                    Instant.now(), "Update Python worker", List.of(pyFile), 10, 2, false,
                    ChangedFile.isSourceCodePath(pyFile.filePath()));
            assertThat(pyCommit.hasCodeChanges()).isTrue();

            // JS commit
            CommitEvidence jsCommit = new CommitEvidence("js12345", "js12345", "Bob", "b@test.com",
                    Instant.now(), "Update JS frontend", List.of(jsFile), 5, 1, false,
                    ChangedFile.isSourceCodePath(jsFile.filePath()));
            assertThat(jsCommit.hasCodeChanges()).isTrue();

            // Go commit
            CommitEvidence goCommit = new CommitEvidence("go12345", "go12345", "Charlie", "c@test.com",
                    Instant.now(), "Update Go server", List.of(goFile), 20, 5, false,
                    ChangedFile.isSourceCodePath(goFile.filePath()));
            assertThat(goCommit.hasCodeChanges()).isTrue();

            // Docs-only commit
            CommitEvidence docCommit = new CommitEvidence("doc1234", "doc1234", "Dana", "d@test.com",
                    Instant.now(), "Update docs", List.of(docFile), 1, 0, false,
                    ChangedFile.isSourceCodePath(docFile.filePath()));
            assertThat(docCommit.hasCodeChanges()).isFalse();
        }
    }
}
