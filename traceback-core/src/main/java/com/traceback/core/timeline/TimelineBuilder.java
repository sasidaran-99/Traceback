package com.traceback.core.timeline;

import com.traceback.core.git.ChangedFile;
import com.traceback.core.git.CommitEvidence;
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
        List<TimelineEvent> events = new ArrayList<>();

        Instant failureBoundary = failureOnset;
        if (failureBoundary == null && logEvidence != null && logEvidence.firstObservedFailure() != null) {
            failureBoundary = logEvidence.firstObservedFailure();
        }

        // 1. Add Git Commits
        if (commits != null) {
            for (CommitEvidence commit : commits) {
                boolean isPre = failureBoundary == null || !commit.timestamp().isAfter(failureBoundary);
                String evidenceId = "EVD-GIT-" + commit.shortSha();

                Map<String, Object> meta = new HashMap<>();
                meta.put("author", commit.authorName());
                meta.put("filesCount", commit.changedFiles().size());
                meta.put("linesAdded", commit.totalLinesAdded());
                meta.put("linesDeleted", commit.totalLinesDeleted());

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

                            events.add(new TimelineEvent(
                                    UUID.randomUUID().toString(),
                                    commit.timestamp(),
                                    "CONFIG_DELTA",
                                    "GIT",
                                    commit.shortSha() + ":" + cf.filePath(),
                                    "Config delta in " + cf.filePath(),
                                    "Modified configuration keys: " + cf.configChanges().keySet(),
                                    isPre,
                                    "EVD-CFG-" + commit.shortSha(),
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
                boolean isPre = failureBoundary != null && !err.timestamp().isAfter(failureBoundary);
                String evidenceId = "EVD-LOG-" + err.lineNumber();

                Map<String, Object> meta = new HashMap<>();
                meta.put("thread", err.thread());
                meta.put("logger", err.logger());
                meta.put("line", err.lineNumber());

                events.add(new TimelineEvent(
                        UUID.randomUUID().toString(),
                        err.timestamp(),
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

            events.add(new TimelineEvent(
                    UUID.randomUUID().toString(),
                    stackTime,
                    "RUNTIME_EXCEPTION",
                    "STACK_TRACE",
                    root.exceptionType(),
                    "Crash: " + root.exceptionType(),
                    root.message(),
                    false,
                    "EVD-STACK-001",
                    meta
            ));
        }

        // Sort strictly chronologically
        events.sort(Comparator.comparing(TimelineEvent::timestamp));
        return events;
    }
}
