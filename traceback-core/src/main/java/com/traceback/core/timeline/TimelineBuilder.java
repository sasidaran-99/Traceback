package com.traceback.core.timeline;

import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
import com.traceback.core.log.LogEntry;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconstructs a strictly chronological investigation timeline integrating Git commits,
 * configuration deltas, and application error events without presuming causality.
 */
@Component
public class TimelineBuilder {

    /**
     * Builds the unified chronological timeline.
     *
     * @param commits      collected Git commits
     * @param logEvidence  parsed application log evidence
     * @param parsedStack  parsed standalone stack trace (if any)
     * @param failureOnset timestamp of the first observed failure (or null if none)
     * @return chronologically sorted timeline events
     */
    public List<TimelineEvent> buildTimeline(List<CommitEvidence> commits,
                                             LogEvidence logEvidence,
                                             ParsedException parsedStack,
                                             Instant failureOnset) {
        return buildTimeline(commits, logEvidence, parsedStack, failureOnset, null);
    }

    /**
     * Builds the unified chronological timeline including working tree filesystem anomalies.
     *
     * @param commits             collected Git commits
     * @param logEvidence         parsed application log evidence
     * @param parsedStack         parsed standalone stack trace (if any)
     * @param failureOnset        timestamp of the first observed failure (or null if none)
     * @param workingTreeSnapshot forensic working tree snapshot (if available)
     * @return chronologically sorted timeline events
     */
    public List<TimelineEvent> buildTimeline(List<CommitEvidence> commits,
                                             LogEvidence logEvidence,
                                             ParsedException parsedStack,
                                             Instant failureOnset,
                                             WorkingTreeSnapshot workingTreeSnapshot) {
        List<TimelineEvent> events = new ArrayList<>();

        boolean isWorkingTreeMode = workingTreeSnapshot != null && workingTreeSnapshot.hasChanges();
        Instant failureBoundary = failureOnset;
        if (failureBoundary == null && logEvidence != null && logEvidence.firstObservedFailure() != null) {
            failureBoundary = logEvidence.firstObservedFailure();
        } else if (failureBoundary == null && isWorkingTreeMode) {
            failureBoundary = workingTreeSnapshot.observedAt();
        }

        // 1. Add Git Commits
        if (commits != null) {
            for (CommitEvidence commit : commits) {
                boolean isPre;
                String classification;
                if (isWorkingTreeMode) {
                    isPre = false;
                    classification = "HISTORICAL_CONTEXT";
                } else if (failureBoundary == null) {
                    isPre = true;
                    classification = "PRE_FAILURE";
                } else if (!commit.timestamp().isAfter(failureBoundary)) {
                    isPre = true;
                    classification = "PRE_FAILURE";
                } else {
                    isPre = false;
                    classification = "POST_FAILURE";
                }

                String evidenceId = "EVD-GIT-" + commit.shortSha();

                Map<String, Object> meta = new HashMap<>();
                meta.put("author", commit.authorName());
                meta.put("filesCount", commit.changedFiles().size());
                meta.put("linesAdded", commit.totalLinesAdded());
                meta.put("linesDeleted", commit.totalLinesDeleted());
                meta.put("eventClassification", classification);

                String title = "Commit: " + commit.commitMessage();
                if (title.length() > 80) {
                    title = title.substring(0, 77) + "...";
                }

                events.add(new TimelineEvent(
                        UUID.randomUUID().toString(),
                        commit.timestamp(),
                        commit.hasConfigurationChanges() ? "CONFIG_CHANGE" : "COMMIT",
                        "GIT",
                        commit.commitSha(),
                        title,
                        commit.commitMessage(),
                        isPre,
                        evidenceId,
                        meta
                ));

                // If commit modified configs, emit an explicit config change timeline event
                if (commit.hasConfigurationChanges()) {
                    for (ChangedFile cf : commit.changedFiles()) {
                        if (!cf.configChanges().isEmpty()) {
                            Map<String, Object> cfgMeta = new HashMap<>();
                            cfgMeta.put("filePath", cf.filePath());
                            cfgMeta.put("changes", cf.configChanges());
                            cfgMeta.put("eventClassification", classification);

                            events.add(new TimelineEvent(
                                    UUID.randomUUID().toString(),
                                    commit.timestamp(),
                                    "CONFIG_DELTA",
                                    "GIT",
                                    commit.shortSha() + ":" + cf.filePath(),
                                    "Config delta in " + cf.filePath(),
                                    "Modified configuration keys: " + cf.configChanges().keySet(),
                                    isPre,
                                    ConcreteEvidenceRegistry.configEvidenceId(commit.shortSha()),
                                    cfgMeta
                            ));
                        }
                    }
                }
            }
        }

        // 2. Add Log Errors
        if (logEvidence != null && logEvidence.errorEntries() != null) {
            for (LogEntry err : logEvidence.errorEntries()) {
                boolean isPre;
                String classification;
                if (isWorkingTreeMode) {
                    isPre = false;
                    classification = "HISTORICAL_CONTEXT";
                } else if (failureBoundary != null && err.timestamp().isBefore(failureBoundary)) {
                    isPre = true;
                    classification = "PRE_FAILURE";
                } else if (failureBoundary != null && err.timestamp().equals(failureBoundary)) {
                    isPre = false;
                    classification = "FAILURE";
                } else {
                    isPre = false;
                    classification = failureBoundary == null ? "PRE_FAILURE" : "POST_FAILURE";
                }

                String evidenceId = "EVD-LOG-" + err.lineNumber();

                Map<String, Object> meta = new HashMap<>();
                meta.put("thread", err.thread());
                meta.put("logger", err.logger());
                meta.put("line", err.lineNumber());
                meta.put("eventClassification", classification);

                Instant logTimestamp = err.timestamp() != null ? err.timestamp() : (failureBoundary != null ? failureBoundary : Instant.now());
                events.add(new TimelineEvent(
                        UUID.randomUUID().toString(),
                        logTimestamp,
                        "RUNTIME_ERROR",
                        "LOG",
                        "log-line-" + err.lineNumber(),
                        "Error in " + err.logger(),
                        err.message(),
                        isPre,
                        evidenceId,
                        meta
                ));
            }
        }

        // 3. Add Standalone Stack Trace if timestamp is available or anchor to failure onset
        if (parsedStack != null && events.stream().noneMatch(e -> "STACK_TRACE".equals(e.source()))) {
            Instant stackTime = failureBoundary != null ? failureBoundary : Instant.now();
            ParsedException root = parsedStack.getRootCause();

            Map<String, Object> meta = new HashMap<>();
            meta.put("exceptionType", root.exceptionType());
            meta.put("topFrame", root.getTopFrame().map(f -> f.declaringClass() + "." + f.methodName()).orElse("unknown"));
            meta.put("eventClassification", isWorkingTreeMode ? "HISTORICAL_CONTEXT" : "FAILURE");

            events.add(new TimelineEvent(
                    UUID.randomUUID().toString(),
                    stackTime,
                    "RUNTIME_EXCEPTION",
                    "STACK_TRACE",
                    root.exceptionType(),
                    "Crash: " + root.exceptionType(),
                    root.message(),
                    false,
                    "EVD-STACK-ROOT",
                    meta
            ));
        }

        // 4. Add Working Tree Anomaly event if discrepancies exist
        if (workingTreeSnapshot != null && workingTreeSnapshot.hasChanges()) {
            Instant wtTime = workingTreeSnapshot.observedAt();
            String evidenceId = !workingTreeSnapshot.getMissingFiles().isEmpty()
                    ? workingTreeSnapshot.getMissingFiles().get(0).evidenceId()
                    : (workingTreeSnapshot.changes().isEmpty() ? "EVD-WT-001" : workingTreeSnapshot.changes().get(0).evidenceId());

            List<String> allEvidenceIds = workingTreeSnapshot.changes().stream()
                    .map(com.traceback.core.git.workingtree.WorkingTreeEvidence::evidenceId)
                    .toList();

            Map<String, Object> meta = new HashMap<>();
            meta.put("missingCount", workingTreeSnapshot.missingTrackedCount());
            meta.put("modifiedCount", workingTreeSnapshot.getModifiedFiles().size());
            meta.put("untrackedCount", workingTreeSnapshot.getUntrackedFiles().size());
            meta.put("headCommitSha", workingTreeSnapshot.headCommitSha());
            meta.put("affectedModules", new ArrayList<>(workingTreeSnapshot.groupByAffectedModule().keySet()));
            meta.put("allEvidenceIds", allEvidenceIds);
            meta.put("eventClassification", "CURRENT_FORENSIC_OBSERVATION");

            events.add(new TimelineEvent(
                    UUID.randomUUID().toString(),
                    wtTime,
                    "WORKING_TREE_ANOMALY",
                    "WORKING_TREE",
                    "working-tree-forensics",
                    "Working tree anomaly: " + workingTreeSnapshot.generateSummary(),
                    "Detected uncommitted filesystem state: " + workingTreeSnapshot.generateSummary(),
                    false,
                    evidenceId,
                    meta
            ));
        }

        // Sort strictly chronologically, nulls last
        events.sort(Comparator.comparing(TimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }
}
