package com.traceback.core.evaluation;

import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.Incident;
import com.traceback.core.domain.Relationship;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic evaluation harness for empirical benchmarking of Traceback against ground-truth incident scenarios.
 * Zero ground-truth leakage: labels and expectations exist strictly within this test harness.
 */
public class EvaluationHarness {

    private final CorrelationEngine correlationEngine;

    public EvaluationHarness() {
        this.correlationEngine = new CorrelationEngine();
    }

    public EvaluationHarness(CorrelationEngine correlationEngine) {
        this.correlationEngine = correlationEngine;
    }

    /**
     * Executes the standard suite of 5 incident scenarios and returns an auditable evaluation summary.
     */
    public EvaluationSummary runStandardBenchmark() {
        List<EvaluationScenario> scenarios = buildStandardScenarios();
        List<EvaluationScenarioResult> scenarioResults = new ArrayList<>();

        long totalTime = 0;
        int causalCount = 0;
        int causalTop1Matches = 0;
        int causalTop3Matches = 0;
        int insufficientCount = 0;
        int correctAbstentions = 0;
        int totalRelationships = 0;
        int totalFalsePositives = 0;

        for (EvaluationScenario scn : scenarios) {
            EvidenceRegistry registry = new ConcreteEvidenceRegistry();
            Incident incident = new Incident(
                    UUID.randomUUID(),
                    "INC-" + scn.scenarioId(),
                    scn.title(),
                    scn.serviceName(),
                    scn.environment(),
                    "HIGH",
                    "INVESTIGATING",
                    Instant.parse("2026-10-10T14:16:30Z"),
                    null,
                    null,
                    null
            );

            long start = System.currentTimeMillis();
            CorrelationResult result = correlationEngine.correlate(
                    incident,
                    scn.inputCommits(),
                    scn.inputLogEvidence(),
                    scn.inputParsedException(),
                    registry,
                    60
            );
            long elapsed = System.currentTimeMillis() - start;
            totalTime += elapsed;

            List<Hypothesis> ranked = result.rankedHypotheses();
            Hypothesis top = ranked.isEmpty() ? null : ranked.get(0);

            boolean top1 = false;
            boolean top3 = false;
            boolean abstained = !result.evidenceSufficient() ||
                    (top != null && top.getSummary().contains("INSUFFICIENT_EVIDENCE"));

            if (scn.expectAbstention()) {
                insufficientCount++;
                if (abstained) {
                    correctAbstentions++;
                }
            } else {
                causalCount++;
                if (top != null && top.getSummary().contains(scn.expectedRootCauseSha())) {
                    top1 = true;
                }
                for (int i = 0; i < Math.min(3, ranked.size()); i++) {
                    if (ranked.get(i).getSummary().contains(scn.expectedRootCauseSha())) {
                        top3 = true;
                        break;
                    }
                }
                if (top1) causalTop1Matches++;
                if (top3) causalTop3Matches++;
            }

            // Calculate false positive relationships
            int scnRelationships = result.relationships().size();
            int scnFp = 0;
            for (Relationship rel : result.relationships()) {
                for (String misleadingSha : scn.misleadingShas()) {
                    if (rel.getExplanation().contains(misleadingSha) && rel.getConfidenceWeight() > 0.40) {
                        scnFp++;
                    }
                }
            }

            totalRelationships += scnRelationships;
            totalFalsePositives += scnFp;

            scenarioResults.add(new EvaluationScenarioResult(
                    scn.scenarioId(),
                    scn.title(),
                    top1,
                    top3,
                    abstained,
                    scnRelationships,
                    scnFp,
                    elapsed,
                    top
            ));
        }

        double top1Acc = causalCount > 0 ? (double) causalTop1Matches / causalCount : 1.0;
        double top3Acc = causalCount > 0 ? (double) causalTop3Matches / causalCount : 1.0;
        double fprr = totalRelationships > 0 ? (double) totalFalsePositives / totalRelationships : 0.0;
        double abstentionRate = insufficientCount > 0 ? (double) correctAbstentions / insufficientCount : 1.0;
        double avgTime = scenarios.isEmpty() ? 0.0 : (double) totalTime / scenarios.size();

        return new EvaluationSummary(
                scenarios.size(),
                causalCount,
                insufficientCount,
                top1Acc,
                top3Acc,
                fprr,
                abstentionRate,
                totalTime,
                avgTime,
                scenarioResults
        );
    }

    private List<EvaluationScenario> buildStandardScenarios() {
        List<EvaluationScenario> list = new ArrayList<>();

        // 1. Scenario A: Code Regression (Inverted fee math in PaymentFeeCalculator.java:58)
        ChangedFile codeFile = new ChangedFile("PaymentFeeCalculator.java", "MODIFY", 5, 2, "patch", false, Collections.emptyMap());
        CommitEvidence causalCodeCommit = new CommitEvidence("c1a89b3", "c1a89b3", "Alice", "alice@example.com",
                Instant.parse("2026-10-10T14:10:00Z"), "Refactor promotional discount math in fee calculator",
                List.of(codeFile), 5, 2, false, true);

        ChangedFile docsFile = new ChangedFile("README.md", "MODIFY", 2, 0, "patch", false, Collections.emptyMap());
        CommitEvidence misleadingDocsCommit = new CommitEvidence("e55b112", "e55b112", "Bob", "bob@example.com",
                Instant.parse("2026-10-10T14:13:30Z"), "Update README with promo code docs",
                List.of(docsFile), 2, 0, false, false);

        StackTraceFrame frameA = StackTraceFrame.of("com.traceback.payment.service.PaymentFeeCalculator", "calculateTransactionFee", "PaymentFeeCalculator.java", 58);
        ParsedException exceptionA = new ParsedException("com.traceback.payment.exception.PaymentCalculationException", "Invalid net payment calculation: fee deduction exceeds gross total", List.of(frameA), null);

        list.add(new EvaluationScenario(
                "SCN-A-CODE",
                "Scenario A: Payment Fee Calculation Code Regression",
                "payment-service",
                "production",
                List.of(misleadingDocsCommit, causalCodeCommit),
                null,
                exceptionA,
                "c1a89b3",
                false,
                List.of("e55b112")
        ));

        // 2. Scenario B: Database Configuration Regression (Wrong port 5433)
        ChangedFile cfgFile = new ChangedFile("application.properties", "MODIFY", 1, 1, "patch", true, Map.of("spring.datasource.url", "jdbc:postgresql://db:5433/pay"));
        CommitEvidence causalConfigCommit = new CommitEvidence("b22109a", "b22109a", "DevOps", "devops@example.com",
                Instant.parse("2026-10-10T15:05:00Z"), "Update datasource URL port for read replica",
                List.of(cfgFile), 1, 1, true, false);

        ChangedFile cssFile = new ChangedFile("checkout.css", "MODIFY", 10, 2, "patch", false, Collections.emptyMap());
        CommitEvidence misleadingCssCommit = new CommitEvidence("a991d4e", "a991d4e", "Carol", "carol@example.com",
                Instant.parse("2026-10-10T15:00:00Z"), "Refactor checkout button CSS styling",
                List.of(cssFile), 10, 2, false, false);

        ParsedException exceptionB = new ParsedException("org.postgresql.util.PSQLException", "Connection to db:5433 refused", Collections.emptyList(), null);

        list.add(new EvaluationScenario(
                "SCN-B-CONFIG",
                "Scenario B: Database Connectivity Port Configuration Error",
                "payment-service",
                "production",
                List.of(causalConfigCommit, misleadingCssCommit),
                null,
                exceptionB,
                "b22109a",
                false,
                List.of("a991d4e")
        ));

        // 3. Scenario C: External Dependency Degradation (Stripe 504 Timeout)
        ChangedFile avatarFile = new ChangedFile("UserProfileAvatar.tsx", "MODIFY", 4, 1, "patch", false, Collections.emptyMap());
        CommitEvidence innocentFrontendCommit = new CommitEvidence("f881c2b", "f881c2b", "Dave", "dave@example.com",
                Instant.parse("2026-10-10T16:00:00Z"), "Refactor UserProfileAvatar layout and icons",
                List.of(avatarFile), 4, 1, false, false);

        ParsedException exceptionC = new ParsedException("com.traceback.payment.exception.PaymentGatewayTimeoutException", "Read timed out connecting to api.stripe.com/v1/charges", Collections.emptyList(), null);

        list.add(new EvaluationScenario(
                "SCN-C-EXTERNAL",
                "Scenario C: External Stripe Gateway Timeout (Innocent Frontend Commit)",
                "payment-service",
                "production",
                List.of(innocentFrontendCommit),
                null,
                exceptionC,
                null,
                true, // Expect abstention from blaming Dave's avatar commit
                List.of("f881c2b")
        ));

        // 4. Scenario D: Misleading Commit Scenario (Database timeout reduction vs auth log change)
        ChangedFile timeoutFile30s = new ChangedFile("application.properties", "MODIFY", 1, 1, "patch", true, Map.of("spring.datasource.hikari.connection-timeout", "30000"));
        CommitEvidence commit30s = new CommitEvidence("c111000", "c111000", "DevOps", "devops@example.com",
                Instant.parse("2026-10-10T13:45:00Z"), "Initial configuration with 30s connection timeout",
                List.of(timeoutFile30s), 1, 1, true, false);

        ChangedFile timeoutFile3s = new ChangedFile("application.properties", "MODIFY", 1, 1, "patch", true, Map.of("spring.datasource.hikari.connection-timeout", "3000"));
        CommitEvidence commit3s = new CommitEvidence("c222000", "c222000", "Alice", "alice@example.com",
                Instant.parse("2026-10-10T14:10:00Z"), "Tune database connection timeout to 3s",
                List.of(timeoutFile3s), 1, 1, true, false);

        ChangedFile authFile = new ChangedFile("AuthService.java", "MODIFY", 2, 1, "patch", false, Collections.emptyMap());
        CommitEvidence commitAuth = new CommitEvidence("c333000", "c333000", "Bob", "bob@example.com",
                Instant.parse("2026-10-10T14:14:00Z"), "Rename authentication sign-in message in AuthService",
                List.of(authFile), 2, 1, false, true);

        ParsedException timeoutEx = new ParsedException("java.sql.SQLTimeoutException", "Connection timeout after 3000ms", Collections.emptyList(), null);

        list.add(new EvaluationScenario(
                "SCN-D-MISLEADING",
                "Scenario D: Misleading Commit (Timeout reduction vs Recent Auth change)",
                "payment-service",
                "production",
                List.of(commitAuth, commit3s, commit30s),
                null,
                timeoutEx,
                "c222000", // Expect 3s timeout commit to rank #1
                false,
                List.of("c333000") // Auth commit must not be attributed
        ));

        // 5. Scenario E: Insufficient Evidence Scenario (Runtime error with zero candidate commits in window)
        ParsedException generalEx = new ParsedException("java.lang.IllegalStateException", "Unexpected service state", Collections.emptyList(), null);

        list.add(new EvaluationScenario(
                "SCN-E-INSUFFICIENT",
                "Scenario E: Insufficient Evidence (Empty Candidate Window)",
                "payment-service",
                "production",
                Collections.emptyList(),
                null,
                generalEx,
                null,
                true, // Expect explicit abstention
                Collections.emptyList()
        ));

        return list;
    }
}
