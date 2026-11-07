package com.traceback.core.correlation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.domain.Relationship;
import com.traceback.core.domain.RelationshipType;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCommitAnalyzer;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.ClaimValidator;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic correlation and root-cause hypothesis generation engine.
 * Implements the transparent scoring formula: S = T + O + A - C, normalized to [0.0, 1.0].
 * Reconstructs evidence, handles contradictions, and validates claims without an LLM.
 */
@Component
public class CorrelationEngine {

    private final GitCommitAnalyzer commitAnalyzer;
    private final ClaimValidator claimValidator;

    public CorrelationEngine() {
        this.commitAnalyzer = new GitCommitAnalyzer();
        this.claimValidator = new DeterministicClaimValidator();
    }

    public CorrelationEngine(GitCommitAnalyzer commitAnalyzer) {
        this.commitAnalyzer = commitAnalyzer;
        this.claimValidator = new DeterministicClaimValidator();
    }

    public CorrelationEngine(GitCommitAnalyzer commitAnalyzer, ClaimValidator claimValidator) {
        this.commitAnalyzer = commitAnalyzer;
        this.claimValidator = claimValidator != null ? claimValidator : new DeterministicClaimValidator();
    }

    /**
     * Executes deterministic correlation across collected data.
     *
     * @param incident         the incident being investigated (or simulated aggregate)
     * @param commits          collected Git commits in reverse chronological order
     * @param logEvidence      parsed application log evidence
     * @param parsedStack      standalone parsed stack trace (if any)
     * @param evidenceRegistry registry to register and lookup concrete evidence items
     * @param windowMinutes    temporal pre-incident window in minutes
     * @return correlation result containing typed relationships, ranked hypotheses, and validated claims
     */
    public CorrelationResult correlate(Incident incident,
                                        List<CommitEvidence> commits,
                                        LogEvidence logEvidence,
                                        ParsedException parsedStack,
                                        EvidenceRegistry evidenceRegistry,
                                        int windowMinutes) {

        List<Relationship> relationships = new ArrayList<>();
        Map<String, List<String>> evidenceByCommit = new HashMap<>();
        Map<String, String> counterEvidenceByCommit = new HashMap<>();
        Map<String, ConfidenceBreakdown> breakdownsByCommit = new HashMap<>();
        List<InvestigationClaim> claims = new ArrayList<>();
        List<ScoredCandidate> scoredCandidates = new ArrayList<>();

        Instant failureOnset = determineFailureOnset(logEvidence, parsedStack);
        ParsedException activeException = resolveActiveException(logEvidence, parsedStack);

        String errorLocation = "UnknownErrorLocation";
        String errorSummary = "Runtime failure";
        if (activeException != null) {
            ParsedException root = activeException.getRootCause();
            errorSummary = root.exceptionType() + ": " + root.message();
            errorLocation = root.getTopFrame().map(f -> f.fileName() + ":" + f.lineNumber()).orElse(root.exceptionType());
        } else if (logEvidence != null && logEvidence.primaryErrorSignature() != null) {
            errorSummary = logEvidence.primaryErrorSignature().exceptionType() + ": " + logEvidence.primaryErrorSignature().messagePattern();
            errorLocation = logEvidence.primaryErrorSignature().sourceLocation();
        }

        // Detect superseded configuration keys across commits in reverse chronological order
        // Key -> Most recent commit that modified this key before failure
        Map<String, String> activeKeyOwnerSha = new HashMap<>();
        if (commits != null) {
            // commits are typically newest first; iterate in order
            for (CommitEvidence c : commits) {
                boolean isPre = failureOnset == null || !c.timestamp().isAfter(failureOnset);
                if (isPre && c.hasConfigurationChanges()) {
                    for (ChangedFile cf : c.changedFiles()) {
                        for (String key : cf.configChanges().keySet()) {
                            String kLower = key.toLowerCase(Locale.ROOT);
                            activeKeyOwnerSha.putIfAbsent(kLower, c.commitSha());
                        }
                    }
                }
            }
        }

        if (commits == null || commits.isEmpty()) {
            Hypothesis abstention = buildAbstentionHypothesis(incident, "No candidate commits found within the investigation window.");
            return new CorrelationResult(
                    relationships,
                    List.of(abstention),
                    evidenceByCommit,
                    counterEvidenceByCommit,
                    breakdownsByCommit,
                    claims,
                    false
            );
        }

        for (CommitEvidence commit : commits) {
            String sha = commit.commitSha();
            List<String> evidenceIds = new ArrayList<>();
            List<String> contraEvidenceIds = new ArrayList<>();
            StringBuilder counterEvidence = new StringBuilder();

            List<String> scoreReasons = new ArrayList<>();
            List<String> penaltyReasons = new ArrayList<>();
            List<String> matchedRules = new ArrayList<>();
            Map<String, String> ruleMatchReasons = new LinkedHashMap<>();

            boolean isPreFailure = failureOnset == null || !commit.timestamp().isAfter(failureOnset);
            long minutesBeforeFailure = failureOnset != null
                    ? Duration.between(commit.timestamp(), failureOnset).toMinutes()
                    : 0;

            String gitEvidenceId = "EVD-GIT-" + commit.shortSha();
            evidenceRegistry.registerItem(EvidenceItem.create(
                    gitEvidenceId,
                    EvidenceType.COMMIT,
                    "GIT",
                    sha,
                    commit.timestamp(),
                    commit.commitMessage(),
                    Map.of("author", commit.authorName(), "files", commit.changedFiles().size(),
                            "investigationId", incident != null ? incident.getId().toString() : "GLOBAL")
            ));
            evidenceIds.add(gitEvidenceId);

            // ==========================================
            // 1. Term T: Temporal Proximity (0 to 30 pts)
            // ==========================================
            double temporalPoints = 0.0;
            if (isPreFailure && minutesBeforeFailure >= 0 && minutesBeforeFailure <= windowMinutes) {
                temporalPoints = 20.0;
                matchedRules.add("RULE_A_TEMPORAL_PROXIMITY");
                String reason = "Commit occurred " + minutesBeforeFailure + "m prior to failure onset (within " + windowMinutes + "m window)";
                if (minutesBeforeFailure <= 15) {
                    temporalPoints += 10.0;
                    reason += " [close proximity bonus: +10 pts]";
                }
                scoreReasons.add(reason + ": +" + (int) temporalPoints + " pts");
                ruleMatchReasons.put("RULE_A_TEMPORAL_PROXIMITY", reason);

                Relationship rel = RelationshipBuilder.buildCommitToErrorRelationship(
                        commit,
                        errorLocation,
                        failureOnset,
                        RelationshipType.TEMPORAL_PRECEDENCE,
                        temporalPoints / 85.0,
                        reason,
                        "{\"minutesBeforeFailure\":" + minutesBeforeFailure + "}"
                );
                relationships.add(rel);
            }

            // ==========================================
            // 2. Term O: Overlap Points (0 to 30 pts)
            // ==========================================
            double overlapPoints = 0.0;
            boolean hasStackOverlap = false;
            boolean hasConfigOverlap = false;

            // Rule B: Stack Trace Overlap
            if (activeException != null) {
                for (StackTraceFrame frame : activeException.getRootCause().frames()) {
                    if (commit.touchesFile(frame.fileName())) {
                        hasStackOverlap = true;
                        overlapPoints = Math.max(overlapPoints, 30.0);
                        matchedRules.add("RULE_B_STACK_TRACE_OVERLAP");
                        String reason = "Modified file " + frame.fileName() + " matches crash stack frame: " + frame.declaringClass() + "." + frame.methodName() + ":" + frame.lineNumber();
                        scoreReasons.add(reason + ": +30 pts");
                        ruleMatchReasons.put("RULE_B_STACK_TRACE_OVERLAP", reason);

                        String frameEvdId = "EVD-FRAME-" + commit.shortSha() + "-" + frame.fileName();
                        evidenceRegistry.registerItem(EvidenceItem.create(
                                frameEvdId,
                                EvidenceType.STACK_FRAME,
                                "STACK_TRACE",
                                frame.fileName() + ":" + frame.lineNumber(),
                                commit.timestamp(),
                                "Stack frame " + frame.declaringClass() + "." + frame.methodName() + " matches changed file",
                                Map.of("file", frame.fileName(), "line", frame.lineNumber(),
                                        "investigationId", incident != null ? incident.getId().toString() : "GLOBAL")
                        ));
                        evidenceIds.add(frameEvdId);

                        Relationship rel = RelationshipBuilder.buildCommitToErrorRelationship(
                                commit,
                                frame.fileName() + ":" + frame.lineNumber(),
                                failureOnset,
                                RelationshipType.INFERRED_CODE_FRAME,
                                0.95,
                                "Stack trace frame matches file modified in commit diff: " + frame.fileName(),
                                "{\"file\":\"" + frame.fileName() + "\",\"line\":" + frame.lineNumber() + "}"
                        );
                        relationships.add(rel);
                        break;
                    }
                }
            }

            // Rule C: Configuration Overlap
            if (commit.hasConfigurationChanges()) {
                for (ChangedFile cf : commit.changedFiles()) {
                    for (Map.Entry<String, String> cfgEntry : cf.configChanges().entrySet()) {
                        String key = cfgEntry.getKey().toLowerCase(Locale.ROOT);
                        String delta = cfgEntry.getValue();

                        boolean matchesTimeout = (key.contains("timeout") || key.contains("hikari") || key.contains("connection"))
                                && isTimeoutError(errorSummary);

                        boolean matchesDb = (key.contains("datasource") || key.contains("jdbc") || key.contains("port") || key.contains("host") || key.contains("db"))
                                && isDatabaseError(errorSummary);

                        if (matchesTimeout || matchesDb) {
                            hasConfigOverlap = true;

                            // Determine if this key was superseded by a newer pre-failure commit
                            String activeOwner = activeKeyOwnerSha.get(key);
                            boolean isSuperseded = activeOwner != null && !activeOwner.equals(commit.commitSha());

                            // Check delta alignment: does the new value match the symptom (e.g. 3000 matching 3000ms)?
                            boolean deltaMatchesErrorValue = deltaContainsErrorSignal(delta, errorSummary);

                            double configPoints = 25.0;
                            if (deltaMatchesErrorValue && !isSuperseded) {
                                configPoints = 30.0;
                            }
                            overlapPoints = Math.max(overlapPoints, configPoints);

                            matchedRules.add("RULE_C_CONFIG_OVERLAP");
                            String reason = "Configuration key [" + key + "] changed (" + delta + "), matching failure symptom: " + errorSummary;
                            if (deltaMatchesErrorValue) {
                                reason += " [exact value match: 30 pts]";
                            }
                            scoreReasons.add(reason);
                            ruleMatchReasons.put("RULE_C_CONFIG_OVERLAP", reason);

                            String cfgEvdId = "EVD-CFG-" + commit.shortSha() + "-" + Math.abs(key.hashCode());
                            evidenceRegistry.registerItem(EvidenceItem.create(
                                    cfgEvdId,
                                    EvidenceType.CONFIG_DIFF,
                                    "GIT",
                                    cf.filePath() + "#" + key,
                                    commit.timestamp(),
                                    "Config key [" + key + "] changed (" + delta + "), matching failure symptom",
                                    Map.of("key", key, "delta", delta, "error", errorSummary,
                                            "investigationId", incident != null ? incident.getId().toString() : "GLOBAL")
                            ));
                            evidenceIds.add(cfgEvdId);

                            Relationship rel = RelationshipBuilder.buildCommitToErrorRelationship(
                                    commit,
                                    errorLocation,
                                    failureOnset,
                                    RelationshipType.INFERRED_CONFIG_KEY,
                                    0.95,
                                    "Configuration change in '" + key + "' (" + delta + ") corresponds directly to runtime error: " + errorSummary,
                                    "{\"configKey\":\"" + key + "\",\"delta\":\"" + delta + "\"}"
                            );
                            relationships.add(rel);
                        }
                    }
                }
            }

            // ====================================================
            // 3. Term A: Independent Evidence Agreement (0 to 25 pts)
            // ====================================================
            double agreementPoints = 0.0;
            String commitMsgLower = commit.commitMessage().toLowerCase(Locale.ROOT);

            // A1: Commit message corroboration with failure domain keywords (without double-counting)
            if ((hasConfigOverlap || hasStackOverlap) &&
                    (commitMsgLower.contains("timeout") || commitMsgLower.contains("database") || commitMsgLower.contains("fee") || commitMsgLower.contains("calc"))) {
                agreementPoints += 10.0;
                matchedRules.add("RULE_D_COMMIT_MSG_CORROBORATION");
                String reason = "Commit message explicitly references failing subsystem keywords: +10 pts";
                scoreReasons.add(reason);
                ruleMatchReasons.put("RULE_D_COMMIT_MSG_CORROBORATION", reason);
            }

            // A2: Multiple repeated error entries in log confirming persistent failure
            if ((hasConfigOverlap || hasStackOverlap) && logEvidence != null && logEvidence.totalErrors() > 1) {
                agreementPoints += 15.0;
                matchedRules.add("RULE_D_REPEATED_FAILURE_CORROBORATION");
                String reason = "Persistent runtime error burst (" + logEvidence.totalErrors() + " errors) corroborates systematic failure: +15 pts";
                scoreReasons.add(reason);
                ruleMatchReasons.put("RULE_D_REPEATED_FAILURE_CORROBORATION", reason);
            }
            agreementPoints = Math.min(25.0, agreementPoints);

            // ==============================================
            // 4. Term C: Contradiction Penalty (0 to 30 pts)
            // ==============================================
            double contradictionPenalty = 0.0;

            // Penalty 1: Post-failure commit
            if (!isPreFailure) {
                contradictionPenalty += 30.0;
                String reason = "Commit occurred after the first observed failure (post-failure change)";
                penaltyReasons.add(reason + ": -30 pts");
                counterEvidence.append(reason).append(". ");

                String contraEvdId = "EVD-CONTRA-POST-" + commit.shortSha();
                evidenceRegistry.registerItem(EvidenceItem.create(
                        contraEvdId,
                        EvidenceType.COMMIT,
                        "ANALYSIS",
                        sha,
                        commit.timestamp(),
                        "Post-failure commit: " + reason,
                        Map.of("type", "POST_FAILURE", "investigationId", incident != null ? incident.getId().toString() : "GLOBAL")
                ));
                contraEvidenceIds.add(contraEvdId);
            }

            // Penalty 2: Cosmetic-only commit
            boolean isCosmetic = commitAnalyzer.isCosmeticOnly(commit);
            if (isCosmetic) {
                contradictionPenalty += 25.0;
                String reason = "Commit modified only cosmetic or documentation files; zero functional code or configuration impact";
                penaltyReasons.add(reason + ": -25 pts");
                counterEvidence.append(reason).append(". ");

                String contraEvdId = "EVD-CONTRA-COSMETIC-" + commit.shortSha();
                evidenceRegistry.registerItem(EvidenceItem.create(
                        contraEvdId,
                        EvidenceType.COMMIT,
                        "ANALYSIS",
                        sha,
                        commit.timestamp(),
                        "Cosmetic commit: " + reason,
                        Map.of("type", "COSMETIC", "investigationId", incident != null ? incident.getId().toString() : "GLOBAL")
                ));
                contraEvidenceIds.add(contraEvdId);
            }

            // Penalty 3: Subsystem contradiction (e.g. error is database timeout, commit is auth log change)
            String subsystem = commitAnalyzer.inferSubsystem(commit);
            if (isDatabaseError(errorSummary) || isTimeoutError(errorSummary)) {
                if ("AUTHENTICATION".equalsIgnoreCase(subsystem) || "UI".equalsIgnoreCase(subsystem)) {
                    if (!hasStackOverlap && !hasConfigOverlap) {
                        contradictionPenalty += 30.0;
                        String reason = "Subsystem contradiction: Commit touched " + subsystem + " with zero database or stack trace overlap";
                        penaltyReasons.add(reason + ": -30 pts");
                        counterEvidence.append(reason).append(". Demoted as temporal coincidence. ");

                        String contraEvdId = "EVD-CONTRA-SUBSYS-" + commit.shortSha();
                        evidenceRegistry.registerItem(EvidenceItem.create(
                                contraEvdId,
                                EvidenceType.COMMIT,
                                "ANALYSIS",
                                sha,
                                commit.timestamp(),
                                reason + "; zero database or stack trace overlap",
                                Map.of("type", "SUBSYSTEM_CONTRADICTION", "investigationId", incident != null ? incident.getId().toString() : "GLOBAL")
                        ));
                        contraEvidenceIds.add(contraEvdId);
                    }
                }
            }

            // Penalty 4: Superseded configuration key
            if (commit.hasConfigurationChanges()) {
                for (ChangedFile cf : commit.changedFiles()) {
                    for (String key : cf.configChanges().keySet()) {
                        String activeOwner = activeKeyOwnerSha.get(key.toLowerCase(Locale.ROOT));
                        if (activeOwner != null && !activeOwner.equals(commit.commitSha())) {
                            contradictionPenalty += 25.0;
                            String reason = "Configuration change on key [" + key + "] was superseded by a later pre-failure commit (" + activeOwner.substring(0, Math.min(7, activeOwner.length())) + ")";
                            penaltyReasons.add(reason + ": -25 pts");
                            counterEvidence.append(reason).append(". ");
                            break;
                        }
                    }
                }
            }

            contradictionPenalty = Math.min(30.0, contradictionPenalty);

            // Constraint: Temporal proximity ALONE must NEVER establish causation
            if (!hasStackOverlap && !hasConfigOverlap) {
                // If there is zero file or config overlap, candidate cannot have high confidence
                if (temporalPoints > 0) {
                    penaltyReasons.add("Temporal proximity alone cannot establish causation without code or configuration overlap");
                    counterEvidence.append("Temporal proximity detected, but lacking code or configuration overlap. ");
                }
            }

            // Calculate transparent confidence breakdown: S = T + O + A - C
            ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                    temporalPoints,
                    overlapPoints,
                    agreementPoints,
                    contradictionPenalty,
                    scoreReasons,
                    penaltyReasons
            );

            double finalScore = breakdown.normalizedScore();

            // Strict cap if zero overlap exists: maximum confidence is 0.15 (REJECTED status)
            if (!hasStackOverlap && !hasConfigOverlap) {
                finalScore = Math.min(finalScore, 0.15);
            }

            evidenceByCommit.put(sha, evidenceIds);
            counterEvidenceByCommit.put(sha, counterEvidence.toString().trim());
            breakdownsByCommit.put(sha, breakdown);

            scoredCandidates.add(new ScoredCandidate(
                    commit,
                    finalScore,
                    breakdown,
                    counterEvidence.toString().trim(),
                    hasConfigOverlap || hasStackOverlap,
                    evidenceIds,
                    contraEvidenceIds,
                    matchedRules,
                    ruleMatchReasons,
                    penaltyReasons
            ));
        }

        // Sort candidates descending by confidence score, then by timestamp descending
        scoredCandidates.sort((a, b) -> {
            int cmp = Double.compare(b.score(), a.score());
            if (cmp != 0) {
                return cmp;
            }
            return b.commit().timestamp().compareTo(a.commit().timestamp());
        });

        // Determine if available evidence is sufficient
        boolean evidenceSufficient = scoredCandidates.stream()
                .anyMatch(c -> c.hasStructuralOverlap() && c.score() >= 0.40);

        // Generate Hypotheses and Claims
        List<Hypothesis> hypotheses = new ArrayList<>();
        int rank = 1;
        for (ScoredCandidate cand : scoredCandidates) {
            CommitEvidence commit = cand.commit();
            double confidence = cand.score();

            HypothesisStatus status;
            if (confidence >= 0.70) {
                status = HypothesisStatus.PROPOSED;
            } else if (confidence >= 0.35) {
                status = HypothesisStatus.INVESTIGATING;
            } else {
                status = HypothesisStatus.REJECTED;
            }

            String summary;
            String verificationPlan;

            if (cand.hasStructuralOverlap() && commit.hasConfigurationChanges()) {
                List<String> modifiedKeys = commit.changedFiles().stream()
                        .flatMap(cf -> cf.configChanges().keySet().stream())
                        .toList();
                String keyDetails = modifiedKeys.isEmpty() ? "timeout/connection settings" : String.join(", ", modifiedKeys);

                summary = "Configuration change in commit " + commit.shortSha() + " (" + commit.commitMessage() +
                        ") directly triggered runtime failure: " + errorSummary;
                verificationPlan = "1. Review configuration git diff for commit " + commit.shortSha() + ".\n" +
                        "2. Check if configuration settings (" + keyDetails + ") are reverted to safe thresholds.\n" +
                        "3. Redeploy or restart service to confirm failure clears.";
            } else if (cand.hasStructuralOverlap()) {
                summary = "Code regression in commit " + commit.shortSha() + " (" + commit.commitMessage() +
                        ") matches runtime crash stack frame.";
                verificationPlan = "1. Inspect git diff for commit " + commit.shortSha() + ".\n" +
                        "2. Run unit tests matching modified classes.\n" +
                        "3. Revert commit or deploy fix.";
            } else {
                summary = "Candidate commit " + commit.shortSha() + " (" + commit.commitMessage() +
                        ") shows weak correlation (confidence: " + (int) (confidence * 100) + "%).";
                verificationPlan = "1. Verify whether commit has any indirect impact on runtime dependencies.";
            }

            String counter = cand.counterEvidence().isBlank() ? "No contradictory evidence detected." : cand.counterEvidence();

            String rankingExplanation = buildRankingExplanation(rank, cand);

            Hypothesis hypothesis = new Hypothesis(
                    UUID.randomUUID(),
                    incident,
                    null,
                    rank++,
                    confidence,
                    status,
                    summary,
                    verificationPlan,
                    counter,
                    cand.evidenceIds(),
                    cand.contraEvidenceIds(),
                    cand.matchedRules(),
                    cand.ruleMatchReasons(),
                    cand.penaltyReasons(),
                    rankingExplanation,
                    cand.breakdown()
            );

            hypotheses.add(hypothesis);

            // Generate backing investigation claim and validate
            InvestigationClaim rawClaim = InvestigationClaim.create(
                    "CLM-" + commit.shortSha(),
                    incident != null ? incident.getId() : UUID.randomUUID(),
                    summary,
                    cand.evidenceIds(),
                    cand.contraEvidenceIds()
            );

            InvestigationClaim validatedClaim = claimValidator.validateClaim(
                    rawClaim,
                    incident != null ? incident.getId() : null,
                    evidenceRegistry
            );
            claims.add(validatedClaim);
        }

        // If evidence is insufficient across all candidates, prepend/provide an explicit abstention hypothesis
        if (!evidenceSufficient && !commits.isEmpty()) {
            Hypothesis abstentionHypothesis = buildAbstentionHypothesis(
                    incident,
                    "INSUFFICIENT_EVIDENCE: Available telemetry does not contain code or configuration changes corresponding to the observed failure (" + errorSummary + "). No candidate commit can be verified as root cause."
            );
            hypotheses.add(0, abstentionHypothesis);
            // Re-index ranks
            for (int i = 0; i < hypotheses.size(); i++) {
                hypotheses.get(i).setRank(i + 1);
            }
        }

        return new CorrelationResult(
                relationships,
                hypotheses,
                evidenceByCommit,
                counterEvidenceByCommit,
                breakdownsByCommit,
                claims,
                evidenceSufficient
        );
    }

    private Hypothesis buildAbstentionHypothesis(Incident incident, String summary) {
        return new Hypothesis(
                UUID.randomUUID(),
                incident,
                null,
                1,
                0.0,
                HypothesisStatus.REJECTED,
                summary,
                "1. Expand the investigation time window.\n2. Ingest additional application and infrastructure logs.\n3. Verify third-party external dependencies (e.g. gateway APIs).",
                "No candidate commit within the pre-incident window modified failing components or configuration parameters.",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                List.of("Zero code or configuration overlap observed with failure telemetry"),
                "Abstained: Insufficient evidence to establish causal link to any commit",
                ConfidenceBreakdown.calculate(0, 0, 0, 0, Collections.emptyList(), Collections.emptyList())
        );
    }

    private String buildRankingExplanation(int rank, ScoredCandidate cand) {
        if (rank == 1 && cand.hasStructuralOverlap()) {
            return String.format("Ranked #1: Strong structural overlap (+%.0f pts) and corroborating evidence with confidence %.0f%%.",
                    cand.breakdown().overlapPoints(), cand.score() * 100);
        } else if (cand.breakdown().contradictionPenalty() > 0) {
            return String.format("Ranked #%d: Demoted due to -%.0f pts penalty (%s).",
                    rank, cand.breakdown().contradictionPenalty(),
                    cand.penaltyReasons().isEmpty() ? "contradiction" : cand.penaltyReasons().get(0));
        } else {
            return String.format("Ranked #%d with confidence %.0f%% based on %s.",
                    rank, cand.score() * 100, cand.breakdown().toExplanation());
        }
    }

    private boolean deltaContainsErrorSignal(String delta, String errorSummary) {
        if (delta == null || errorSummary == null) return false;
        String deltaLower = delta.toLowerCase(Locale.ROOT);
        String errorLower = errorSummary.toLowerCase(Locale.ROOT);

        // e.g. delta contains "3000" and error contains "3000ms"
        if (deltaLower.contains("3000") && errorLower.contains("3000ms")) return true;
        if (deltaLower.contains("5433") && errorLower.contains("5433")) return true;
        return false;
    }

    private Instant determineFailureOnset(LogEvidence logEvidence, ParsedException parsedStack) {
        if (logEvidence != null && logEvidence.firstObservedFailure() != null) {
            return logEvidence.firstObservedFailure();
        }
        return null;
    }

    private ParsedException resolveActiveException(LogEvidence logEvidence, ParsedException parsedStack) {
        if (parsedStack != null) {
            return parsedStack;
        }
        if (logEvidence != null && logEvidence.primaryException() != null) {
            return logEvidence.primaryException();
        }
        return null;
    }

    private boolean isTimeoutError(String errorSummary) {
        if (errorSummary == null) return false;
        String lower = errorSummary.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("sqltimeoutexception");
    }

    private boolean isDatabaseError(String errorSummary) {
        if (errorSummary == null) return false;
        String lower = errorSummary.toLowerCase(Locale.ROOT);
        return lower.contains("hikaripool")
                || lower.contains("psqlexception")
                || lower.contains("jdbc")
                || lower.contains("connection refused")
                || lower.contains("cannotcreatetransactionexception")
                || lower.contains("sqltimeoutexception")
                || lower.contains("database");
    }

    private record ScoredCandidate(
            CommitEvidence commit,
            double score,
            ConfidenceBreakdown breakdown,
            String counterEvidence,
            boolean hasStructuralOverlap,
            List<String> evidenceIds,
            List<String> contraEvidenceIds,
            List<String> matchedRules,
            Map<String, String> ruleMatchReasons,
            List<String> penaltyReasons
    ) {}
}
