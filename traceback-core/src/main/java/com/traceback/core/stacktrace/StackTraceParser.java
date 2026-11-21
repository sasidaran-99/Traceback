package com.traceback.core.stacktrace;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Universal composite parser that delegates multiline stack trace analysis
 * to language-specific {@link StackTraceAdapter} implementations (Java, Python, Node.js).
 */
@Component
public class StackTraceParser {

    private final List<StackTraceAdapter> adapters;

    public StackTraceParser() {
        this.adapters = List.of(
                new PythonStackTraceAdapter(),
                new NodeStackTraceAdapter(),
                new JavaStackTraceAdapter()
        );
    }

    @Autowired
    public StackTraceParser(List<StackTraceAdapter> adapters) {
        if (adapters == null || adapters.isEmpty()) {
            this.adapters = List.of(
                    new PythonStackTraceAdapter(),
                    new NodeStackTraceAdapter(),
                    new JavaStackTraceAdapter()
            );
        } else {
            // Ensure Python and Node are evaluated before fallback Java adapter
            List<StackTraceAdapter> ordered = new ArrayList<>(adapters);
            ordered.sort((a, b) -> {
                if (a instanceof PythonStackTraceAdapter) return -1;
                if (b instanceof PythonStackTraceAdapter) return 1;
                if (a instanceof NodeStackTraceAdapter) return -1;
                if (b instanceof NodeStackTraceAdapter) return 1;
                return 0;
            });
            this.adapters = List.copyOf(ordered);
        }
    }

    /**
     * Parses a multiline stack trace string from any supported language (Java, Python, Node.js).
     *
     * @param rawStackTrace raw stack trace text
     * @return Optional containing parsed exception, or empty if input is blank or unparseable
     */
    public Optional<ParsedException> parse(String rawStackTrace) {
        if (rawStackTrace == null || rawStackTrace.isBlank()) {
            return Optional.empty();
        }

        for (StackTraceAdapter adapter : adapters) {
            if (adapter.canParse(rawStackTrace)) {
                Optional<ParsedException> result = adapter.parse(rawStackTrace);
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        // Fallback: try each adapter in order if canParse was not definitive
        for (StackTraceAdapter adapter : adapters) {
            Optional<ParsedException> fallback = adapter.parse(rawStackTrace);
            if (fallback.isPresent()) {
                return fallback;
            }
        }

        return Optional.empty();
    }
}
