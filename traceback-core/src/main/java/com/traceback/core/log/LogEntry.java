package com.traceback.core.log;

import com.traceback.core.security.SecretRedactor;
import com.traceback.core.stacktrace.ParsedException;

import java.time.Instant;

/**
 * Parsed individual log entry from application log streams.
 */
public record LogEntry(
        Instant timestamp,
        String level,
        String thread,
        String logger,
        String message,
        String stackTraceRaw,
        ParsedException parsedException,
        int lineNumber
) {
    public LogEntry {
        message = message != null ? SecretRedactor.getInstance().redact(message) : "";
        stackTraceRaw = stackTraceRaw != null ? SecretRedactor.getInstance().redact(stackTraceRaw) : "";
    }
    public boolean isError() {
        return "ERROR".equalsIgnoreCase(level);
    }

    public boolean isWarn() {
        return "WARN".equalsIgnoreCase(level) || "WARNING".equalsIgnoreCase(level);
    }

    public boolean hasException() {
        return parsedException != null || (stackTraceRaw != null && !stackTraceRaw.isBlank());
    }
}
