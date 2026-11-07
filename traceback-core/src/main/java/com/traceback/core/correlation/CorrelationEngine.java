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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic correlation and root-cause hypothesis generation engine.
 * Correlates Git commits, diffs, configuration deltas, and error telemetry without LLM hallucinations.
 */
@Component
public class CorrelationEngine {

    private final GitCommitAnalyzer commitAnalyzer;

    public CorrelationEngine() {
        this.commitAnalyzer = new GitCommitAnalyzer();
    }

    public CorrelationEngine(GitCommitAnalyzer commitAnalyzer) {
        this.commitAnalyzer = commitAnalyzer;
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
     * @return correlation result containing typed relationships and ranked hypotheses
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

        if (commits == null || commits.isEmpty()) {
            return new CorrelationResult(relationships, Collections.emptyList(), evidenceByCommit, counterEvidenceByCommit);
        }

        for (CommitEvidence commit : commits) {
            String sha = commit.commitSha();
            List<String> evidenceIds = new ArrayList<>();
            StringBuilder counterEvidence = new StringBuilder();

            double score = 0.0;
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
                    Map.of("author", commit.authorName(), "files", commit.changedFiles().size())
            ));
            evidenceIds.add(gitEvidenceId);

            // 1. Rule A: Temporal Proximity
            if (isPreFailure && minutesBeforeFailure >= 0 && minutesBeforeFailure <= windowMinutes) {
                double temporalWeight = 0.30;
                // Slight bonus for tighter proximity
                if (minutesBeforeFailure <= 15) {
                    temporalWeight += 0.05;
                }
                score += temporalWeight;

                Relationship rel = RelationshipBuilder.buildCommitToErrorRelationship(
                        commit,
                        errorLocation,
                        failureOnset,
                        RelationshipType.TEMPORAL_PRECEDENCE,
                        temporalWeight,
                        "Commit occurred " + minutesBeforeFailure + " minute(s) prior to first observed failure symptom",
                        "{\"minutesBeforeFailure\":" + minutesBeforeFailure + "}"
                );
                relationships.add(rel);
            } else if (!isPreFailure) {
                counterEvidence.append("Commit occurred after the first observed failure (post-failure change). ");
            }

            // 2. Rule B: Stack Trace Overlap
            boolean hasStackOverlap = false;
            if (activeException != null) {
                for (StackTraceFrame frame : activeException.getRootCause().frames()) {
                    if (commit.touchesFile(frame.fileName())) {
                        hasStackOverlap = true;
                        score += 0.50;

                        String frameEvdId = "EVD-FRAME-" + commit.shortSha() + "-" + frame.fileName();
                        evidenceRegistry.registerItem(EvidenceItem.create(
                                frameEvdId,
                                EvidenceType.STACK_FRAME,
                                "STACK_TRACE",
                                frame.fileName() + ":" + frame.lineNumber(),
                                commit.timestamp(),
                                "Stack frame " + frame.declaringClass() + "." + frame.methodName() + " matches changed file",
                                Map.of("file", frame.fileName(), "line", frame.lineNumber())
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

            // 3. Rule C: Configuration Overlap
            boolean hasConfigOverlap = false;
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
                            score += 0.65;

                            String cfgEvdId = "EVD-CFG-" + commit.shortSha() + "-" + key.hashCode();
                            evidenceRegistry.registerItem(EvidenceItem.create(
                                    cfgEvdId,
                                    EvidenceType.CONFIG_DIFF,
                                    "GIT",
                                    cf.filePath() + "#" + key,
                                    commit.timestamp(),
                                    "Config key [" + key + "] changed (" + delta + "), matching failure symptom",
                                    Map.of("key", key, "delta", delta, "error", errorSummary)
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

            // 4. Rule D: Error Signature Matching in Commit Message
            String commitMsgLower = commit.commitMessage().toLowerCase(Locale.ROOT);
            if (isTimeoutError(errorSummary) && commitMsgLower.contains("timeout")) {
                score += 0.15;
            }

            // 5. Rule E: Contradiction Handling & Demotion
            boolean isCosmetic = commitAnalyzer.isCosmeticOnly(commit);
            if (isCosmetic) {
                score = score * 0.20;
                counterEvidence.append("Commit modified only cosmetic or documentation files; zero runtime code impact. ");
            }

            // Subsystem contradiction: e.g. error is database timeout, but commit is auth log change
            String subsystem = commitAnalyzer.inferSubsystem(commit);
            if (isDatabaseError(errorSummary) || isTimeoutError(errorSummary)) {
                if ("AUTHENTICATION".equalsIgnoreCase(subsystem) || "UI".equalsIgnoreCase(subsystem)) {
                    if (!hasStackOverlap && !hasConfigOverlap) {
                        // Heavily demote innocent commit despite temporal proximity
                        score = Math.min(score, 0.12);
                        counterEvidence.append("Temporal proximity detected, but commit touched ").append(subsystem)
                                .append(" with zero database or stack trace overlap. Demoted as temporal coincidence. ");
                    }
                }
            }

            if (!isPreFailure) {
                score = 0.05;
            }

            score = Math.min(1.0, Math.max(0.0, score));

            evidenceByCommit.put(sha, evidenceIds);
            counterEvidenceByCommit.put(sha, counterEvidence.toString().trim());

            scoredCandidates.add(new ScoredCandidate(commit, score, counterEvidence.toString().trim(), hasConfigOverlap || hasStackOverlap));
        }

        // Sort candidates descending by confidence score, then by timestamp descending
        scoredCandidates.sort((a, b) -> {
            int cmp = Double.compare(b.score(), a.score());
            if (cmp != 0) {
                return cmp;
            }
            return b.commit().timestamp().compareTo(a.commit().timestamp());
        });

        // Generate Hypotheses
        List<Hypothesis> hypotheses = new ArrayList<>();
        int rank = 1;
        for (ScoredCandidate cand : scoredCandidates) {
            CommitEvidence commit = cand.commit();
            double confidence = Math.round(cand.score() * 100.0) / 100.0;

            HypothesisStatus status = HypothesisStatus.PROPOSED;
            if (confidence >= 0.80) {
                status = HypothesisStatus.PROPOSED;
            } else if (confidence < 0.20) {
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
                        ") shows weak correlation (confidence: " + (int)(confidence * 100) + "%).";
                verificationPlan = "1. Verify whether commit has any indirect impact on runtime dependencies.";
            }

            String counter = cand.counterEvidence().isBlank() ? "No contradictory evidence detected." : cand.counterEvidence();

            Hypothesis hypothesis = new Hypothesis(
                    UUID.randomUUID(),
                    incident,
                    null, // Candidate event can be linked when persisted
                    rank++,
                    confidence,
                    status,
                    summary,
                    verificationPlan,
                    counter
            );

            hypotheses.add(hypothesis);
        }

        return new CorrelationResult(relationships, hypotheses, evidenceByCommit, counterEvidenceByCommit);
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

    private record ScoredCandidate(CommitEvidence commit, double score, String counterEvidence, boolean hasStructuralOverlap) {}
}
