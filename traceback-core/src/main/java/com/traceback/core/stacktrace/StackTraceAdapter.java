package com.traceback.core.stacktrace;

import java.util.Optional;

/**
 * Strategy interface for parsing multiline stack traces across different programming languages and runtimes.
 */
public interface StackTraceAdapter {

    /**
     * Determines whether this adapter can parse the provided raw stack trace text.
     */
    boolean canParse(String rawStackTrace);

    /**
     * Parses the stack trace into a normalized ParsedException object.
     */
    Optional<ParsedException> parse(String rawStackTrace);

    /**
     * The language or runtime ecosystem supported by this adapter (e.g. "Java", "Python", "NodeJS").
     */
    String getSupportedLanguage();
}
