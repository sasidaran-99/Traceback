package com.traceback.core.git.workingtree;

import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.git.GitCollector;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TacitIqWorkingTreeForensicsTest {

    private static final String ENV_VAR_REPO_PATH = "TACITIQ_REPO_PATH";
    private static final String SYS_PROP_REPO_PATH = "tacitiq.repo.path";

    @Test
    @DisplayName("Should detect and investigate real-world TacitIQ EconomicTimes 79 missing tracked files")
    void shouldInvestigateTacitIqIncident() {
        String repoPathStr = System.getenv(ENV_VAR_REPO_PATH);
        if (repoPathStr == null || repoPathStr.isBlank()) {
            repoPathStr = System.getProperty(SYS_PROP_REPO_PATH);
        }

        Assumptions.assumeTrue(
                repoPathStr != null && !repoPathStr.isBlank(),
                "TACITIQ_REPO_PATH environment variable (or -Dtacitiq.repo.path) is not configured; safely skipping acceptance test."
        );

        Path repoPath = Path.of(repoPathStr);
        Assumptions.assumeTrue(
                Files.exists(repoPath) && Files.isDirectory(repoPath),
                "TacitIQ EconomicTimes repository not found at " + repoPathStr + "; safely skipping acceptance test."
        );

        // 1. Initialize Collector with TacitIQ authorized in allowed roots
        List<String> allowed = List.of(repoPath.toString());
        GitCollector gitCollector = new GitCollector(allowed);
        GitWorkingTreeCollector wtCollector = new GitWorkingTreeCollector(gitCollector, allowed);

        // 2. Read-only collect working tree snapshot
        WorkingTreeSnapshot snapshot = wtCollector.collectWorkingTree(repoPath.toString());

        // 3. Verify exact counts and status
        assertThat(snapshot.hasMissingTrackedFiles()).isTrue();
        assertThat(snapshot.missingTrackedCount()).isEqualTo(79);
        assertThat(snapshot.getModifiedFiles()).hasSize(1);
        assertThat(snapshot.getModifiedFiles().get(0).relativePath()).isEqualTo("README.md");
        assertThat(snapshot.headCommitSha()).isEqualTo("d711d65a3e2fecd40257af992993bfab07fb7a24");

        // 4. Verify grouping by architectural module
        Map<String, List<WorkingTreeEvidence>> moduleMap = snapshot.groupByAffectedModule();
        assertThat(moduleMap.get("backend/main")).hasSize(73);
        assertThat(moduleMap.get("backend/db/migration")).hasSize(5);
        assertThat(moduleMap.get("backend/test")).hasSize(1);

        // 5. Verify grouping by file extension
        Map<String, Long> extCounts = snapshot.countMissingByExtension();
        assertThat(extCounts.get(".java")).isEqualTo(74L); // 73 in main + 1 in test
        assertThat(extCounts.get(".sql")).isEqualTo(5L);  // 5 Flyway migrations

        // 6. Verify evidence properties for all 79 missing files
        List<WorkingTreeEvidence> missingFiles = snapshot.getMissingFiles();
        assertThat(missingFiles).hasSize(79);

        for (WorkingTreeEvidence wte : missingFiles) {
            assertThat(wte.changeType()).isEqualTo(WorkingTreeChangeType.MISSING);
            assertThat(wte.stagingState()).isEqualTo(WorkingTreeStagingState.UNSTAGED);
            assertThat(wte.expectedInHead()).isTrue();
            assertThat(wte.physicallyExists()).isFalse();
            assertThat(wte.isReadable()).isFalse();
            assertThat(wte.evidenceId()).startsWith("EVD-WT-MISSING-");
            assertThat(wte.headCommitSha()).isEqualTo("d711d65a3e2fecd40257af992993bfab07fb7a24");
        }

        // 7. Execute end-to-end deterministic investigation
        InvestigationService investigationService = new InvestigationService(
                gitCollector,
                wtCollector,
                new StackTraceParser(),
                new LogAnalyzer(),
                new TimelineBuilder(),
                new CorrelationEngine(new com.traceback.core.git.GitCommitAnalyzer(), new DeterministicClaimValidator()),
                new ConcreteEvidenceRegistry()
        );

        InvestigationResult result = investigationService.startInvestigation(
                repoPath.toString(),
                null,
                null,
                null,
                "economic-times-backend",
                "production",
                60,
                50
        );

        // 8. Assert Rank #1 Hypothesis
        assertThat(result.hypotheses()).isNotEmpty();
        var topHypothesis = result.hypotheses().getFirst();
        assertThat(topHypothesis.getRank()).isEqualTo(1);
        assertThat(topHypothesis.getConfidenceScore()).isEqualTo(0.85);
        assertThat(topHypothesis.getSummary())
                .isEqualTo("Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.");
        assertThat(topHypothesis.getCounterEvidence())
                .isEqualTo("No committed deletions exist in the repository history for the 79 missing files.");
        assertThat(topHypothesis.getSupportingEvidenceIds()).hasSize(79);

        // 9. Assert Claim Validation
        var claims = result.claims();
        var wtClaim = claims.stream().filter(c -> "CLM-WT-ANOMALY".equals(c.claimId())).findFirst();
        assertThat(wtClaim).isPresent();
        assertThat(wtClaim.get().status()).isEqualTo(ClaimValidationStatus.SUPPORTED);

        // 10. Execute Autonomous Agent Investigation Report
        BifrostClient bifrostClient = org.mockito.Mockito.mock(BifrostClient.class);
        org.mockito.Mockito.when(bifrostClient.isAvailable()).thenReturn(false);
        TavilyResearchService tavilyService = org.mockito.Mockito.mock(TavilyResearchService.class);

        InvestigationAgent agent = new InvestigationAgent(
                bifrostClient,
                tavilyService,
                new DeterministicClaimValidator(),
                investigationService.getEvidenceRegistry(),
                investigationService
        );

        InvestigationAgentReport report = agent.investigate(result);
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.CONFIRMED);
        assertThat(report.primarySuspectedRootCause()).contains("Working tree filesystem anomaly");
        assertThat(report.remainingUncertainties())
                .anyMatch(u -> u.contains("Git evidence confirms physical absence") && u.contains("cannot establish which process"));
        assertThat(report.remainingUncertainties())
                .anyMatch(u -> u.contains("No committed deletion exists"));
    }
}
