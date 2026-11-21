package com.traceback.core.audit;

import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitDiffAnalyzer;
import com.traceback.core.git.workingtree.WorkingTreeChangeType;
import com.traceback.core.git.workingtree.WorkingTreeEvidence;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
import com.traceback.core.git.workingtree.WorkingTreeStagingState;
import com.traceback.core.security.SecretRedactor;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceBack 2.0 — Forensic Integrity, Evidence Isolation & Pipeline Consistency Audit Tests")
class ForensicIntegrityAndIsolationAuditTest {

    private ConcreteEvidenceRegistry registry;
    private DeterministicClaimValidator claimValidator;
    private TimelineBuilder timelineBuilder;

    @BeforeEach
    void setUp() {
        registry = new ConcreteEvidenceRegistry();
        claimValidator = new DeterministicClaimValidator();
        timelineBuilder = new TimelineBuilder();
    }

    @Nested
    @DisplayName("P0: Evidence Ownership & Cross-Investigation Isolation")
    class EvidenceIsolationTests {

        @Test
        @DisplayName("Evidence items registered under Investigation A are isolated from Investigation B")
        void shouldIsolateEvidenceBetweenInvestigations() {
            UUID invA = UUID.randomUUID();
            UUID invB = UUID.randomUUID();

            EvidenceItem itemA = EvidenceItem.create(
                    "EVD-GIT-aaa1111",
                    EvidenceType.COMMIT,
                    "GIT",
                    "commit aaa1111",
                    Instant.now(),
                    "Investigation A commit",
                    Map.of("investigationId", invA.toString())
            );

            EvidenceItem itemB = EvidenceItem.create(
                    "EVD-GIT-bbb2222",
                    EvidenceType.COMMIT,
                    "GIT",
                    "commit bbb2222",
                    Instant.now(),
                    "Investigation B commit",
                    Map.of("investigationId", invB.toString())
            );

            registry.registerItem(itemA, invA);
            registry.registerItem(itemB, invB);

            // Scoped lookup for invA
            Optional<EvidenceItem> foundA = registry.findItemById("EVD-GIT-aaa1111", invA);
            assertThat(foundA).isPresent();
            assertThat(foundA.get().evidenceId()).isEqualTo("EVD-GIT-aaa1111");

            // Scoped lookup for invB looking for invA's evidence
            Optional<EvidenceItem> foreignLookup = registry.findItemById("EVD-GIT-aaa1111", invB);
            assertThat(foreignLookup).isEmpty();

            // Scoped list queries
            List<EvidenceItem> itemsForA = registry.findByInvestigationId(invA);
            assertThat(itemsForA).hasSize(1);
            assertThat(itemsForA.get(0).evidenceId()).isEqualTo("EVD-GIT-aaa1111");

            List<EvidenceItem> itemsForB = registry.findByInvestigationId(invB);
            assertThat(itemsForB).hasSize(1);
            assertThat(itemsForB.get(0).evidenceId()).isEqualTo("EVD-GIT-bbb2222");

            // Typed query isolation
            List<EvidenceItem> typedA = registry.findItemsByType(EvidenceType.COMMIT, invA);
            assertThat(typedA).hasSize(1);
            assertThat(typedA.get(0).evidenceId()).isEqualTo("EVD-GIT-aaa1111");

            // Source query isolation
            List<EvidenceItem> sourcedB = registry.findItemsBySource("GIT", invB);
            assertThat(sourcedB).hasSize(1);
            assertThat(sourcedB.get(0).evidenceId()).isEqualTo("EVD-GIT-bbb2222");
        }

        @Test
        @DisplayName("Claim citing foreign evidence from another investigation fails with VALIDATION_ERROR")
        void shouldRejectClaimCitingForeignEvidence() {
            UUID invA = UUID.randomUUID();
            UUID invB = UUID.randomUUID();

            EvidenceItem itemB = EvidenceItem.create(
                    "EVD-GIT-bbb2222",
                    EvidenceType.COMMIT,
                    "GIT",
                    "commit bbb2222",
                    Instant.now(),
                    "Investigation B commit",
                    Map.of("investigationId", invB.toString())
            );
            registry.registerItem(itemB, invB);

            // Claim asserted by invA citing invB's evidence
            InvestigationClaim foreignClaim = InvestigationClaim.create(
                    "CLM-FOREIGN-TEST",
                    invA,
                    "Commit bbb2222 caused failure in service A",
                    List.of("EVD-GIT-bbb2222"),
                    Collections.emptyList()
            );

            InvestigationClaim validated = claimValidator.validateClaim(foreignClaim, invA, registry);
            assertThat(validated.status()).isEqualTo(ClaimValidationStatus.VALIDATION_ERROR);
            assertThat(validated.explanation()).contains("belongs to a different investigation");
        }
    }

    @Nested
    @DisplayName("P0: Separate Evidence Integrity from Semantic Grounding")
    class EvidenceIntegrityVsSemanticGroundingTests {

        @Test
        @DisplayName("Valid SHA-256 hash verifies integrity, but cosmetic commit fails causal substantiation")
        void shouldDistinguishIntegrityFromCausalRelevance() {
            UUID invId = UUID.randomUUID();

            // Create a cosmetic commit evidence item
            EvidenceItem cosmeticItem = EvidenceItem.create(
                    "EVD-GIT-cosmetic1",
                    EvidenceType.COMMIT,
                    "GIT",
                    "commit cosmetic1",
                    Instant.now(),
                    "Update README.md (cosmetic commit - no functional change)",
                    Map.of("investigationId", invId.toString(), "isCosmetic", true)
            );
            registry.registerItem(cosmeticItem, invId);

            // 1. Cryptographic integrity must be valid
            assertThat(claimValidator.verifyEvidenceIntegrity(cosmeticItem)).isTrue();

            // 2. But claim asserting causal root cause must be rejected as UNSUPPORTED
            InvestigationClaim causalClaim = InvestigationClaim.create(
                    "CLM-COSMETIC-CAUSAL",
                    invId,
                    "Commit cosmetic1 is the confirmed root cause of failure.",
                    List.of("EVD-GIT-cosmetic1"),
                    Collections.emptyList()
            );

            InvestigationClaim validated = claimValidator.validateClaim(causalClaim, invId, registry);
            assertThat(validated.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
            assertThat(validated.explanation()).contains("cosmetic");
        }

        @Test
        @DisplayName("Commit with 0% heuristic confidence cannot substantiate causal claim")
        void shouldRejectCausalClaimWithZeroConfidenceCommit() {
            UUID invId = UUID.randomUUID();

            EvidenceItem zeroConfItem = EvidenceItem.create(
                    "EVD-GIT-zero1",
                    EvidenceType.COMMIT,
                    "GIT",
                    "commit zero1",
                    Instant.now(),
                    "Minor whitespace fix",
                    Map.of("confidence", 0.0, "investigationId", invId.toString())
            );
            registry.registerItem(zeroConfItem, invId);

            InvestigationClaim claim = InvestigationClaim.create(
                    "CLM-ZERO-CONF",
                    invId,
                    "Commit zero1 is a probable contributing change for service failure.",
                    List.of("EVD-GIT-zero1"),
                    Collections.emptyList()
            );

            InvestigationClaim validated = claimValidator.validateClaim(claim, invId, registry);
            assertThat(validated.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
            assertThat(validated.explanation()).contains("0% heuristic correlation confidence");
        }

        @Test
        @DisplayName("Working tree missing files prove absence, but reject uncorroborated process attribution")
        void shouldEnforceFactualMissingFilesWithoutHallucinatedProcessAttribution() {
            UUID invId = UUID.randomUUID();

            WorkingTreeEvidence wtEvd = new WorkingTreeEvidence(
                    "EVD-WT-MISSING-1",
                    "repo",
                    "src/main/OrderService.java",
                    WorkingTreeChangeType.MISSING,
                    WorkingTreeStagingState.UNSTAGED,
                    "head123",
                    false,
                    false,
                    false,
                    Instant.now(),
                    "WORKING_TREE",
                    "root",
                    true,
                    1024L,
                    Instant.now()
            );
            EvidenceItem wtItem = wtEvd.toEvidenceItem(invId);
            registry.registerItem(wtItem, invId);

            // 1. Factual statement of missing files succeeds
            InvestigationClaim factualClaim = InvestigationClaim.create(
                    "CLM-FACTUAL-MISSING",
                    invId,
                    "Working tree anomaly: 1 tracked files missing from disk (present in HEAD).",
                    List.of(wtItem.evidenceId()),
                    Collections.emptyList()
            );
            InvestigationClaim validatedFactual = claimValidator.validateClaim(factualClaim, invId, registry);
            assertThat(validatedFactual.status()).isEqualTo(ClaimValidationStatus.SUPPORTED);
            assertThat(validatedFactual.explanation()).contains("Supported by 1 verified evidence item(s)");

            // 2. Unverified process attribution claim fails
            InvestigationClaim attributionClaim = InvestigationClaim.create(
                    "CLM-PROCESS-ATTRIBUTION",
                    invId,
                    "OneDrive synchronization deleted the missing working tree files.",
                    List.of(wtItem.evidenceId()),
                    Collections.emptyList()
            );
            InvestigationClaim validatedAttribution = claimValidator.validateClaim(attributionClaim, invId, registry);
            assertThat(validatedAttribution.status()).isEqualTo(ClaimValidationStatus.UNSUPPORTED);
            assertThat(validatedAttribution.explanation()).contains("corroborating system audit log");
        }
    }

    @Nested
    @DisplayName("P1: Mode-Aware Deterministic Scoring for Working Tree Forensics")
    class ModeAwareScoringTests {

        @Test
        @DisplayName("Working tree forensics score is capped at <= 0.85 and uses forensic terms")
        void shouldScoreWorkingTreeForensicsCorrectly() {
            UUID invId = UUID.randomUUID();
            Incident incident = new Incident(
                    invId, "INC-TEST", "Working tree anomaly test", "payment-service",
                    "prod", "CRITICAL", "OPEN", Instant.now(), null, null, null
            );

            WorkingTreeEvidence wt1 = new WorkingTreeEvidence("EVD-WT-1", "repo", "src/A.java", WorkingTreeChangeType.MISSING, WorkingTreeStagingState.UNSTAGED, "d711d65a", false, false, false, Instant.now(), "WORKING_TREE", "root", true, 100L, Instant.now());
            WorkingTreeEvidence wt2 = new WorkingTreeEvidence("EVD-WT-2", "repo", "src/B.java", WorkingTreeChangeType.MISSING, WorkingTreeStagingState.UNSTAGED, "d711d65a", false, false, false, Instant.now(), "WORKING_TREE", "root", true, 100L, Instant.now());

            WorkingTreeSnapshot snapshot = new WorkingTreeSnapshot(
                    "repo",
                    "d711d65a1234567890abcdef1234567890abcdef",
                    Instant.now(),
                    List.of(wt1, wt2)
            );

            registry.registerItem(wt1.toEvidenceItem(invId), invId);
            registry.registerItem(wt2.toEvidenceItem(invId), invId);

            CorrelationEngine engine = new CorrelationEngine(new com.traceback.core.git.GitCommitAnalyzer(), claimValidator);
            CorrelationResult result = engine.correlate(
                    incident,
                    Collections.emptyList(),
                    null,
                    null,
                    snapshot,
                    registry,
                    60
            );

            assertThat(result.rankedHypotheses()).isNotEmpty();
            Hypothesis top = result.rankedHypotheses().get(0);

            // Normalized score capped at <= 0.85
            assertThat(top.getConfidenceScore()).isLessThanOrEqualTo(0.85);
            assertThat(top.getConfidenceScore()).isEqualTo(0.85);

            ConfidenceBreakdown breakdown = top.getConfidenceBreakdown();
            assertThat(breakdown).isNotNull();
            assertThat(breakdown.normalizedScore()).isLessThanOrEqualTo(0.85);
            assertThat(breakdown.scoreReasons()).anyMatch(r -> r.contains("Filesystem Discrepancy"));
            assertThat(breakdown.scoreReasons()).anyMatch(r -> r.contains("Git HEAD Tree Comparison"));
            assertThat(breakdown.scoreReasons()).anyMatch(r -> r.contains("Git History Corroboration"));
        }
    }

    @Nested
    @DisplayName("P1: Timeline Correctness and Provenance")
    class TimelineProvenanceTests {

        @Test
        @DisplayName("Working tree anomaly classifies historical commits as HISTORICAL_CONTEXT, not PRE_FAILURE")
        void shouldClassifyWorkingTreeTimelineAccurately() {
            Instant tObserved = Instant.parse("2026-11-10T12:00:00Z");
            Instant tCommit1 = Instant.parse("2026-10-01T10:00:00Z");
            Instant tCommit2 = Instant.parse("2026-10-15T10:00:00Z");

            CommitEvidence c1 = new CommitEvidence("sha-1", "sha1", "Dev1", "dev1@test.com", tCommit1, "Old commit 1", Collections.emptyList(), 0, 0, false, false);
            CommitEvidence c2 = new CommitEvidence("sha-2", "sha2", "Dev2", "dev2@test.com", tCommit2, "Old commit 2", Collections.emptyList(), 0, 0, false, false);

            WorkingTreeEvidence wt = new WorkingTreeEvidence("EVD-WT-1", "repo", "src/X.java", WorkingTreeChangeType.MISSING, WorkingTreeStagingState.UNSTAGED, "head123", false, false, false, tObserved, "WORKING_TREE", "root", true, 100L, tObserved);
            WorkingTreeSnapshot snapshot = new WorkingTreeSnapshot("repo", "head123", tObserved, List.of(wt));

            List<TimelineEvent> timeline = timelineBuilder.buildTimeline(
                    List.of(c1, c2),
                    null,
                    null,
                    null,
                    snapshot
            );

            assertThat(timeline).hasSize(3);

            // Commits must be HISTORICAL_CONTEXT and isPreFailure == false
            TimelineEvent event1 = timeline.get(0);
            assertThat(event1.eventClassification()).isEqualTo("HISTORICAL_CONTEXT");
            assertThat(event1.isPreFailure()).isFalse();

            TimelineEvent event2 = timeline.get(1);
            assertThat(event2.eventClassification()).isEqualTo("HISTORICAL_CONTEXT");
            assertThat(event2.isPreFailure()).isFalse();

            // Forensic observation
            TimelineEvent event3 = timeline.get(2);
            assertThat(event3.eventClassification()).isEqualTo("CURRENT_FORENSIC_OBSERVATION");
            assertThat(event3.timestamp()).isEqualTo(tObserved);
            assertThat(event3.isPreFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("P2: Configuration Diff Extraction & Secret Redaction")
    class ConfigurationAndRedactionTests {

        @Test
        @DisplayName("Source code files like .java and .ts are excluded from configuration path detection")
        void shouldExcludeSourceCodeFromConfiguration() {
            assertThat(ChangedFile.isConfigurationPath("src/main/OrderService.java")).isFalse();
            assertThat(ChangedFile.isConfigurationPath("src/components/App.tsx")).isFalse();
            assertThat(ChangedFile.isConfigurationPath("src/utils/calc.ts")).isFalse();
            assertThat(ChangedFile.isConfigurationPath("src/services/configService.js")).isFalse();

            // Legitimate configuration files
            assertThat(ChangedFile.isConfigurationPath("application.properties")).isTrue();
            assertThat(ChangedFile.isConfigurationPath("application.yml")).isTrue();
            assertThat(ChangedFile.isConfigurationPath("config/settings.json")).isTrue();
            assertThat(ChangedFile.isConfigurationPath("docker-compose.yaml")).isTrue();
        }

        @Test
        @DisplayName("Programming language statements are excluded from config diff key extraction")
        void shouldExcludeProgrammingLanguageStatementsFromConfigDiff() {
            GitDiffAnalyzer analyzer = new GitDiffAnalyzer();

            // Ordinary java statements should be ignored
            assertThat(analyzer.extractConfigChanges("this.documentParserService = service;", "application.properties")).isEmpty();
            assertThat(analyzer.extractConfigChanges("this.messagingTemplate = template;", "application.properties")).isEmpty();
            assertThat(analyzer.extractConfigChanges("return procedureConfidence;", "application.properties")).isEmpty();
            assertThat(analyzer.extractConfigChanges("import java.util.List;", "application.properties")).isEmpty();
            assertThat(analyzer.extractConfigChanges("public void execute() {", "application.properties")).isEmpty();

            // Legitimate configuration key-values
            Map<String, String> validConfig = analyzer.extractConfigChanges("+db.connection.timeout=5000\n-db.connection.timeout=30000", "application.properties");
            assertThat(validConfig).containsKey("db.connection.timeout");
        }

        @Test
        @DisplayName("Sensitive credentials, passwords and keys are redacted by SecretRedactor")
        void shouldRedactSensitiveKeysAndValues() {
            SecretRedactor redactor = SecretRedactor.getInstance();

            assertThat(redactor.isSensitiveKey("db.password")).isTrue();
            assertThat(redactor.isSensitiveKey("jwt.secret")).isTrue();
            assertThat(redactor.isSensitiveKey("api_key")).isTrue();
            assertThat(redactor.isSensitiveKey("author_name")).isFalse();

            String text = "Connecting to db with password=super_secret_pwd and token: sk-1234567890abcdef1234567890abcdef";
            String redacted = redactor.redact(text);
            assertThat(redacted).doesNotContain("super_secret_pwd");
            assertThat(redacted).contains(SecretRedactor.REDACTED_MARKER);
        }
    }

    @Nested
    @DisplayName("P1: Truthful Bifrost & Tavily Execution Status")
    class IntegrationStatusTruthfulnessTests {

        @Test
        @DisplayName("Unconfigured integrations record explicit UNCONFIGURED / SKIPPED_UNCONFIGURED status")
        void shouldRecordUnconfiguredStatusesExplicitly() {
            InvestigationAgentReport report = new InvestigationAgentReport(
                    "REP-test1",
                    UUID.randomUUID(),
                    "Test summary",
                    "Root cause",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    "Deterministic fallback reasoning",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    AgentInvestigationStatus.INSUFFICIENT_EVIDENCE,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Instant.now(),
                    "UNCONFIGURED",
                    "SKIPPED_UNCONFIGURED"
            );

            assertThat(report.bifrostExecutionStatus()).isEqualTo("UNCONFIGURED");
            assertThat(report.tavilyExecutionStatus()).isEqualTo("SKIPPED_UNCONFIGURED");
        }
    }
}
