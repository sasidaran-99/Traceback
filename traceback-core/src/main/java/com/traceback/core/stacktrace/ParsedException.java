package com.traceback.core.stacktrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Parsed representation of a Java exception with nested causes and structured frames.
 */
public record ParsedException(
        String exceptionType,
        String message,
        List<StackTraceFrame> frames,
        ParsedException cause
) {
    public ParsedException {
        if (frames == null) {
            frames = Collections.emptyList();
        }
    }

    /**
     * Flattens the exception and all nested causes into a single list from root to outermost.
     */
    public List<ParsedException> getCauseChain() {
        List<ParsedException> chain = new ArrayList<>();
        ParsedException current = this;
        while (current != null) {
            chain.add(current);
            current = current.cause();
        }
        return chain;
    }

    /**
     * Finds the innermost root cause in the chain.
     */
    public ParsedException getRootCause() {
        ParsedException current = this;
        while (current.cause() != null) {
            current = current.cause();
        }
        return current;
    }

    /**
     * Locates the first frame originating from application code (non-framework).
     */
    public Optional<StackTraceFrame> getTopApplicationFrame() {
        for (ParsedException pe : getCauseChain()) {
            for (StackTraceFrame frame : pe.frames()) {
                if (frame.isApplicationFrame()) {
                    return Optional.of(frame);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the very first frame of the root cause.
     */
    public Optional<StackTraceFrame> getTopFrame() {
        ParsedException root = getRootCause();
        if (!root.frames().isEmpty()) {
            return Optional.of(root.frames().get(0));
        }
        if (!frames.isEmpty()) {
            return Optional.of(frames.get(0));
        }
        return Optional.empty();
    }

    /**
     * Formats the exception hierarchy into a readable chain showing wrapper and root cause.
     * e.g. "org.springframework.dao.CannotAcquireLockException (caused by java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 3000ms.)"
     */
    public String formatChain() {
        ParsedException root = getRootCause();
        if (root != null && !root.equals(this)) {
            String rootMsg = root.message() != null && !root.message().isBlank() ? ": " + root.message() : "";
            return exceptionType + " (caused by " + root.exceptionType() + rootMsg + ")";
        }
        String msg = message != null && !message.isBlank() ? ": " + message : "";
        return exceptionType + msg;
    }

    /**
     * Formats exception type with its root cause type if nested.
     * e.g. "org.springframework.dao.CannotAcquireLockException (caused by java.sql.SQLTransientConnectionException)"
     */
    public String formatTypeChain() {
        ParsedException root = getRootCause();
        if (root != null && !root.equals(this)) {
            return exceptionType + " (caused by " + root.exceptionType() + ")";
        }
        return exceptionType;
    }
}
