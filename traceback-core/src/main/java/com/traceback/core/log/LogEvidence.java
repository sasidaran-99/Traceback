package com.traceback.core.log;

import com.traceback.core.stacktrace.ParsedException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Aggregated failure and diagnostic evidence extracted from application logs.
 */
public record LogEvidence(
        Instant firstObservedFailure,
        Instant lastObservedFailure,
        int totalErrors,
        int totalWarnings,
        List<LogEntry> errorEntries,
        Map<ErrorSignature, List<LogEntry>> groupedErrors,
        ErrorSignature primaryErrorSignature,
        ParsedException primaryException
) {
    public LogEvidence {
        if (errorEntries == null) {
            errorEntries = Collections.emptyList();
        }
        if (groupedErrors == null) {
            groupedErrors = Collections.emptyMap();
        }
    }

    public boolean hasFailures() {
        return totalErrors > 0 && firstObservedFailure != null;
    }
}
