package com.traceback.core.correlation;

import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.domain.Relationship;
import com.traceback.core.domain.RelationshipType;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCommitAnalyzer;
import com.traceback.core.git.workingtree.WorkingTreeEvidence;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
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
        return correlate(incident, commits, logEvidence, parsedStack, null, evidenceRegistry, windowMinutes);
    }

    /**
     * Executes deterministic correlation across collected data including working tree forensics.
     *
     * @param incident            the incident being investigated (or simulated aggregate)
     * @param commits             collected Git commits in reverse chronological order
     * @param logEvidence         parsed application log evidence
     * @param parsedStack         standalone parsed stack trace (if any)
     * @param workingTreeSnapshot forensic working tree snapshot (if available)
     * @param evidenceRegistry    registry to register and lookup concrete evidence items
     * @param windowMinutes       temporal pre-incident window in minutes
     * @return correlation result containing typed relationships, ranked hypotheses, and validated claims
     */
    public CorrelationResult correlate(Incident incident,
                                        List<CommitEvidence> commits,
                                        LogEvidence logEvidence,
                                        ParsedException parsedStack,
                                        WorkingTreeSnapshot workingTreeSnapshot,
                                        EvidenceRegistry evidenceRegistry,
                                        int windowMinutes) {

        List<Relationship> relationships = new ArrayList<>();
        Map<String, List<String>> evidenceByCommit = new HashMap<>();
        Map<String, String> counterEvidenceByCommit = new HashMap<>();
        Map<String, ConfidenceBreakdown> breakdownsByCommit = new HashMap<>();
        List<InvestigationClaim> claims = new ArrayList<>();
        List<ScoredCandidate> scoredCandidates = new ArrayList<>();

        Instant failureOnset = determineFailureOnset(logEvidence, parsedStack, incident, commits);
        ParsedException activeException = resolveActiveException(logEvidence, parsedStack);
        boolean hasWorkingTreeAnomaly = workingTreeSnapshot != null && workingTreeSnapshot.hasMissingTrackedFiles();
        UUID invId = incident != null ? incident.getId() : null;

        String errorLocation = "UnknownErrorLocation";
        String errorSummary = hasWorkingTreeAnomaly ? "Working tree anomaly: missing tracked files detected" : "Runtime failure";
        if (activeException != null) {
            ParsedException root = activeException.getRootCause();
            errorSummary = root.exceptionType() + ": " + root.message();
            errorLocation = root.getTopFrame().map(f -> f.fileName() + ":" + f.lineNumber()).orElse(root.exceptionType());
        } else if (logEvidence != null && logEvidence.primaryErrorSignature() != null) {
            errorSummary = logEvidence.primaryErrorSignature().exceptionType() + ": " + logEvidence.primaryErrorSignature().messagePattern();
            errorLocation = logEvidence.primaryErrorSignature().sourceLocation();
        } else if (hasWorkingTreeAnomaly) {
            errorSummary = "Working tree anomaly: missing tracked files detected";
            errorLocation = "WorkingTree";
        } else {
            errorSummary = "No observed runtime errors";
        }

        // Detect superseded configuration keys across commits in reverse chronological order
        // Key -> Most recent commit that modified this key before failure
        Map<String, String> activeKeyOwnerSha = new HashMap<>();
        if (commits != null) {
            // commits are typically newest first; iterate in order
            for (CommitEvidence c : commits) {
                boolean isPre = failureOnset == null || c.timestamp() == null || !c.timestamp().isAfter(failureOnset);
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

        if ((commits == null || commits.isEmpty()) && !hasWorkingTreeAnomaly) {
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

        if (commits != null) {
            for (CommitEvidence commit : commits) {
            String sha = commit.commitSha();
            List<String> evidenceIds = new ArrayList<>();
            List<String> contraEvidenceIds = new ArrayList<>();
            StringBuilder counterEvidence = new StringBuilder();

            List<String> scoreReasons = new ArrayList<>();
            List<String> penaltyReasons = new ArrayList<>();
            List<String> matchedRules = new ArrayList<>();
            Map<String, String> ruleMatchReasons = new LinkedHashMap<>();

            boolean hasTemporalInfo = failureOnset != null && commit.timestamp() != null;
            Duration durationToFailure = hasTemporalInfo ? Duration.between(commit.timestamp(), failureOnset) : null;
            long secondsBeforeFailure = durationToFailure != null ? durationToFailure.getSeconds() : -1;
            long minutesBeforeFailure = secondsBeforeFailure >= 0 ? secondsBeforeFailure / 60 : -1;
            boolean isPreFailure = !hasTemporalInfo || !commit.timestamp().isAfter(failureOnset);

            String invStr = incident != null ? incident.getId().toString() : "GLOBAL";
            String gitEvidenceId = "EVD-GIT-" + commit.shortSha();
            evidenceRegistry.registerItem(EvidenceItem.create(
                    gitEvidenceId,
                    EvidenceType.COMMIT,
                    "GIT",
                    sha,
                    commit.timestamp(),
                    commit.commitMessage(),
                    Map.of("author", commit.authorName(), "files", commit.changedFiles().size(),
                            "investigationId", invStr, "originatingInvestigationId", invStr)
            ), invId);
            evidenceIds.add(gitEvidenceId);

            // Register canonical configuration diff evidence whenever commit has config changes
            if (commit.hasConfigurationChanges()) {
                String cfgEvdId = ConcreteEvidenceRegistry.configEvidenceId(commit.shortSha());
                Map<String, Object> allCfgMeta = new HashMap<>();
                allCfgMeta.put("investigationId", invStr);
                allCfgMeta.put("originatingInvestigationId", invStr);
                allCfgMeta.put("commitSha", sha);
                List<String> files = new ArrayList<>();
                Map<String, String> mergedChanges = new LinkedHashMap<>();
                for (ChangedFile cf : commit.changedFiles()) {
                    if (cf.configChanges() != null && !cf.configChanges().isEmpty()) {
                        files.add(cf.filePath());
                        mergedChanges.putAll(cf.configChanges());
                    }
                }
                allCfgMeta.put("configFiles", files);
                allCfgMeta.put("changes", mergedChanges);

                evidenceRegistry.registerItem(EvidenceItem.create(
                        cfgEvdId,
                        EvidenceType.CONFIG_DIFF,
                        "GIT",
                        commit.shortSha() + ":config",
                        commit.timestamp(),
                        "Configuration delta in commit " + commit.shortSha() + ": " + files + " (" + mergedChanges.keySet() + ")",
                        allCfgMeta
                ), invId);
            }

            // ==========================================
            // 1. Term T: Temporal Proximity (0 to 30 pts)
            // ==========================================
            double temporalPoints = 0.0;
            if (!hasTemporalInfo) {
                temporalPoints = 0.0;
                matchedRules.add("RULE_A_TEMPORAL_UNAVAILABLE");
                String reason = "Temporal information unavailable (missing failure timestamp or commit timestamp); assigned neutral baseline: +0 pts";
                scoreReasons.add(reason);
                ruleMatchReasons.put("RULE_A_TEMPORAL_UNAVAILABLE", reason);
            } else if (isPreFailure) {
                if (secondsBeforeFailure >= 0 && secondsBeforeFailure <= (long) windowMinutes * 60) {
                    temporalPoints = 20.0;
                    matchedRules.add("RULE_A_TEMPORAL_PROXIMITY");
                    String timeStr = formatDuration(secondsBeforeFailure, minutesBeforeFailure);
                    String reason = "Commit occurred " + timeStr + " prior to failure onset (within " + windowMinutes + "m window)";
                    if (secondsBeforeFailure <= 15 * 60) {
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
                            "{\"secondsBeforeFailure\":" + secondsBeforeFailure + ",\"minutesBeforeFailure\":" + minutesBeforeFailure + "}"
                    );
                    relationships.add(rel);
                } else if (secondsBeforeFailure >= 0 && secondsBeforeFailure <= (long) windowMinutes * 120) {
                    temporalPoints = 10.0;
                    matchedRules.add("RULE_A_TEMPORAL_EXTENDED");
                    String timeStr = formatDuration(secondsBeforeFailure, minutesBeforeFailure);
                    String reason = "Commit occurred " + timeStr + " prior to failure onset (extended window " + windowMinutes + "-" + (windowMinutes * 2) + "m)";
                    scoreReasons.add(reason + ": +10 pts");
                    ruleMatchReasons.put("RULE_A_TEMPORAL_EXTENDED", reason);

                    Relationship rel = RelationshipBuilder.buildCommitToErrorRelationship(
                            commit,
                            errorLocation,
                            failureOnset,
                            RelationshipType.TEMPORAL_PRECEDENCE,
                            temporalPoints / 85.0,
                            reason,
                            "{\"secondsBeforeFailure\":" + secondsBeforeFailure + ",\"minutesBeforeFailure\":" + minutesBeforeFailure + "}"
                    );
                    relationships.add(rel);
                } else {
                    temporalPoints = 0.0;
                    String timeStr = formatDuration(secondsBeforeFailure, minutesBeforeFailure);
                    String reason = "Commit occurred " + timeStr + " prior to failure onset (exceeds 2x window of " + (windowMinutes * 2) + "m)";
                    scoreReasons.add(reason + ": +0 pts");
                }
            } else {
                temporalPoints = 0.0;
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
                                        "investigationId", invStr, "originatingInvestigationId", invStr)
                        ), invId);
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

                        boolean matchesDb = (key.contains("datasource") || key.contains("jdbc") || key.contains("port") || key.contains("host") || key.contains("db") || key.contains("database"))
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

                            String cfgEvdId = ConcreteEvidenceRegistry.configEvidenceId(commit.shortSha());
                            evidenceRegistry.registerItem(EvidenceItem.create(
                                    cfgEvdId,
                                    EvidenceType.CONFIG_DIFF,
                                    "GIT",
                                    cf.filePath() + "#" + key,
                                    commit.timestamp(),
                                    "Config key [" + key + "] changed (" + delta + "), matching failure symptom: " + errorSummary,
                                    Map.of("key", key, "delta", delta, "error", errorSummary,
                                            "investigationId", invStr, "originatingInvestigationId", invStr)
                            ), invId);
                            if (!evidenceIds.contains(cfgEvdId)) {
                                evidenceIds.add(cfgEvdId);
                            }

                            Relationship rel = RelationshipBuilder.buildCommitToErrorRelationship(
                                    commit,
                                    errorLocation,
                                    failureOnset,
                                    RelationshipType.INFERRED_CONFIG_KEY,
                                    0.95,
                                    "Configuration change in '" + key + "' (" + delta + ") aligns with runtime error symptom: " + errorSummary,
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
                        Map.of("type", "POST_FAILURE", "investigationId", invStr, "originatingInvestigationId", invStr)
                ), invId);
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
                        Map.of("type", "COSMETIC", "investigationId", invStr, "originatingInvestigationId", invStr)
                ), invId);
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
                                Map.of("type", "SUBSYSTEM_CONTRADICTION", "investigationId", invStr, "originatingInvestigationId", invStr)
                        ), invId);
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

                            String contraEvdId = "EVD-CONTRA-SUPERSEDED-" + commit.shortSha();
                            evidenceRegistry.registerItem(EvidenceItem.create(
                                    contraEvdId,
                                    EvidenceType.CONFIG_DIFF,
                                    "ANALYSIS",
                                    commit.shortSha() + ":superseded",
                                    commit.timestamp(),
                                    reason,
                                    Map.of("type", "SUPERSEDED_CONFIG", "key", key, "supersededBy", activeOwner,
                                            "investigationId", invStr, "originatingInvestigationId", invStr)
                            ), invId);
                            if (!contraEvidenceIds.contains(contraEvdId)) {
                                contraEvidenceIds.add(contraEvdId);
                            }
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
                .anyMatch(c -> c.hasStructuralOverlap() && c.score() >= 0.35);

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
                String keyDetails = modifiedKeys.isEmpty() ? "configuration settings" : String.join(", ", modifiedKeys);

                StringBuilder sb = new StringBuilder();
                sb.append("Potential contributing change in commit ").append(commit.shortSha());
                sb.append(" (\"").append(commit.commitMessage()).append("\"): ");
                sb.append("modified configuration settings (").append(keyDetails).append(")");
                if (errorSummary != null && !errorSummary.isBlank()) {
                    sb.append(" aligning with runtime failure symptom (").append(errorSummary).append(")");
                }
                sb.append(". The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.");
                summary = sb.toString();

                verificationPlan = "1. Review configuration git diff for commit " + commit.shortSha() + ".\n" +
                        "2. Check if configuration settings (" + keyDetails + ") are reverted to safe thresholds.\n" +
                        "3. Verify whether this configuration was active in production during the failure window via deployment/metrics history.";
            } else if (cand.hasStructuralOverlap()) {
                summary = "Code regression in commit " + commit.shortSha() + " (\"" + commit.commitMessage() +
                        "\"): modified file matches runtime crash stack frame. Available evidence supports code correlation, but does not independently establish active deployment timing.";
                verificationPlan = "1. Inspect git diff for commit " + commit.shortSha() + ".\n" +
                        "2. Run unit tests matching modified classes.\n" +
                        "3. Verify deployment logs and revert commit or deploy fix.";
            } else {
                summary = "Candidate commit " + commit.shortSha() + " (\"" + commit.commitMessage() +
                        "\") shows weak correlation (heuristic confidence: " + (int) (confidence * 100) + "%).";
                verificationPlan = "1. Verify whether commit has any indirect impact on runtime dependencies.";
            }

            String counter = cand.counterEvidence().isBlank() ? "No contradictory evidence detected." : cand.counterEvidence();

            String rankingExplanation = buildRankingExplanation(rank, cand);

            Event candidateEvent = new Event(
                    UUID.randomUUID(),
                    commit.hasConfigurationChanges() ? EventType.CONFIG_CHANGE : EventType.COMMIT,
                    EventSource.GITHUB,
                    incident != null ? incident.getServiceName() : "application",
                    incident != null ? incident.getEnvironment() : "production",
                    commit.timestamp(),
                    commit.commitSha(),
                    "{\"message\":\"" + commit.commitMessage().replace("\"", "\\\"") + "\"}"
            );

            Hypothesis hypothesis = new Hypothesis(
                    UUID.randomUUID(),
                    incident,
                    candidateEvent,
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

        // If working tree forensic discrepancies exist, generate top-priority hypothesis
        if (hasWorkingTreeAnomaly) {
            int missingCount = workingTreeSnapshot.missingTrackedCount();
            String headSha = workingTreeSnapshot.headCommitSha();
            String shortHead = (headSha != null && headSha.length() >= 7) ? headSha.substring(0, 7) : (headSha != null ? headSha : "UNKNOWN");

            String summary = String.format(
                    "Working tree anomaly: %d tracked files missing from disk (present in HEAD %s). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.",
                    missingCount, shortHead
            );
            String counterEvidence = String.format(
                    "No committed deletions exist in the repository history for the %d missing files.",
                    missingCount
            );
            String verificationPlan = "1. Verify whether files were removed by an external process, script, or environment synchronization.\n2. Restore missing working-tree files from HEAD using 'git checkout HEAD -- <paths>' if disappearance was unintended.\n3. Review operating system file-system audit logs for process deletion events.";

            List<String> missingEvidenceIds = workingTreeSnapshot.getMissingFiles().stream()
                    .map(WorkingTreeEvidence::evidenceId)
                    .toList();

            ConfidenceBreakdown wtBreakdown = new ConfidenceBreakdown(
                    30.0, 30.0, 25.0, 0.0,
                    85.0, 0.85,
                    List.of(
                            "Filesystem Discrepancy: " + missingCount + " tracked files physically missing from working directory (+30 pts)",
                            "Git HEAD Tree Comparison: Missing files confirmed present in HEAD tree " + shortHead + " (+30 pts)",
                            "Git History Corroboration: No committed deletions exist in repository history (+25 pts)"
                    ),
                    List.of("Causal Process Uncertainty: OS-level process deletion event is not confirmed in audit logs (normalized score capped at 0.85)")
            );

            String rankingExplanation = String.format(
                    "Ranked #1: Working tree anomaly detected with %d missing tracked files present in HEAD %s.",
                    missingCount, shortHead
            );

            Hypothesis wtHypothesis = new Hypothesis(
                    UUID.randomUUID(),
                    incident,
                    null,
                    1,
                    0.85,
                    HypothesisStatus.CONFIRMED,
                    summary,
                    verificationPlan,
                    counterEvidence,
                    missingEvidenceIds,
                    Collections.emptyList(),
                    List.of("RULE_WT_MISSING_TRACKED_FILES"),
                    Map.of("RULE_WT_MISSING_TRACKED_FILES", String.format("%d tracked files physically missing from working directory but retained in HEAD %s", missingCount, shortHead)),
                    Collections.emptyList(),
                    rankingExplanation,
                    wtBreakdown
            );

            // Register in lookup maps
            evidenceByCommit.put("WORKING_TREE", missingEvidenceIds);
            counterEvidenceByCommit.put("WORKING_TREE", counterEvidence);
            breakdownsByCommit.put("WORKING_TREE", wtBreakdown);
            if (headSha != null && !headSha.isBlank()) {
                evidenceByCommit.put(headSha, missingEvidenceIds);
                counterEvidenceByCommit.put(headSha, counterEvidence);
                breakdownsByCommit.put(headSha, wtBreakdown);
            }

            // Generate backing investigation claim and validate
            InvestigationClaim rawClaim = InvestigationClaim.create(
                    "CLM-WT-ANOMALY",
                    incident != null ? incident.getId() : UUID.randomUUID(),
                    summary,
                    missingEvidenceIds,
                    Collections.emptyList()
            );

            InvestigationClaim validatedClaim = claimValidator.validateClaim(
                    rawClaim,
                    incident != null ? incident.getId() : null,
                    evidenceRegistry
            );
            claims.add(0, validatedClaim);

            // Insert wtHypothesis at rank #1
            hypotheses.add(0, wtHypothesis);
            evidenceSufficient = true;
        }

        // If evidence is insufficient across all candidates, prepend/provide an explicit abstention hypothesis
        if (!evidenceSufficient && !commits.isEmpty()) {
            String abstentionReason = (activeException == null && (logEvidence == null || logEvidence.primaryErrorSignature() == null))
                    ? "Available telemetry contains no observed runtime errors or working-tree anomalies. No candidate commit can be verified as root cause."
                    : "Available telemetry does not contain code or configuration changes corresponding to the observed failure (" + errorSummary + "). No candidate commit can be verified as root cause.";
            Hypothesis abstentionHypothesis = buildAbstentionHypothesis(
                    incident,
                    "INSUFFICIENT_EVIDENCE: " + abstentionReason
            );
            hypotheses.add(0, abstentionHypothesis);
        }

        // Re-index ranks
        for (int i = 0; i < hypotheses.size(); i++) {
            hypotheses.get(i).setRank(i + 1);
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

    private static final java.util.regex.Pattern NUMERIC_SIGNAL_PATTERN = java.util.regex.Pattern.compile("\\b(\\d{3,6})\\b");

    private boolean deltaContainsErrorSignal(String delta, String errorSummary) {
        if (delta == null || errorSummary == null) return false;
        String deltaLower = delta.toLowerCase(Locale.ROOT);
        String errorLower = errorSummary.toLowerCase(Locale.ROOT);

        // If delta has "=>", inspect the new value side
        String targetText = deltaLower;
        int arrow = deltaLower.indexOf("=>");
        if (arrow >= 0 && arrow + 2 < deltaLower.length()) {
            targetText = deltaLower.substring(arrow + 2).trim();
        }

        java.util.regex.Matcher matcher = NUMERIC_SIGNAL_PATTERN.matcher(targetText);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (errorLower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Instant determineFailureOnset(LogEvidence logEvidence, ParsedException parsedStack, Incident incident, List<CommitEvidence> commits) {
        if (logEvidence != null && logEvidence.firstObservedFailure() != null) {
            return logEvidence.firstObservedFailure();
        }
        if (incident != null && incident.getStartedAt() != null) {
            // Check if incident startedAt is a plausible failure timestamp relative to the candidate commits
            // (i.e. at least one commit occurred prior to or at incident start)
            if (commits == null || commits.isEmpty() || commits.stream().anyMatch(c -> c.timestamp() != null && !c.timestamp().isAfter(incident.getStartedAt()))) {
                return incident.getStartedAt();
            }
        }
        return null;
    }

    private String formatDuration(long seconds, long minutes) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds % 60 == 0) {
            return minutes + "m";
        } else {
            return minutes + "m " + (seconds % 60) + "s";
        }
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
                || lower.contains("database")
                || lower.contains("operationalerror")
                || lower.contains("databaseerror")
                || lower.contains("sequelizeconnectionerror")
                || lower.contains("econnrefused")
                || lower.contains("redis")
                || lower.contains("mongo")
                || lower.contains("postgres")
                || lower.contains("mysql");
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
