package com.traceback.core.stacktrace;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw Java and Spring Boot multiline stack traces into structured objects.
 */
@Component
public class StackTraceParser {

    private static final Pattern AT_LINE_PATTERN = Pattern.compile(
            "^\\s*at\\s+(?:[\\w./]+/)?([\\w$]+(?:\\.[\\w$]+)+)\\.([\\w$<>]+)\\((?:([^:)]+):(\\d+)|Native Method|Unknown Source)\\)"
    );

    private static final Pattern CAUSED_BY_PATTERN = Pattern.compile(
            "^Caused by:\\s+([\\w$]+(?:\\.[\\w$]+)+)(?::\\s*(.*))?$"
    );

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^(?:[a-zA-Z0-9_.-]+:\\s+)?([\\w$]+(?:\\.[\\w$]+)+Exception|[\\w$]+(?:\\.[\\w$]+)+Error|[\\w$]+(?:\\.[\\w$]+)+Throwable)(?::\\s*(.*))?$"
    );

    /**
     * Parses a multiline Java stack trace string.
     *
     * @param rawStackTrace raw stack trace text
     * @return Optional containing parsed exception, or empty if input is blank or unparseable
     */
    public Optional<ParsedException> parse(String rawStackTrace) {
        if (rawStackTrace == null || rawStackTrace.isBlank()) {
            return Optional.empty();
        }

        try (BufferedReader reader = new BufferedReader(new StringReader(rawStackTrace.trim()))) {
            String firstLine = reader.readLine();
            while (firstLine != null && (firstLine.isBlank() || firstLine.startsWith("#"))) {
                firstLine = reader.readLine();
            }

            if (firstLine == null) {
                return Optional.empty();
            }

            // If first line is a log message or prefix, advance to exception header if present
            if (!isLikelyException(firstLine)) {
                reader.mark(8192);
                String nextLine;
                while ((nextLine = reader.readLine()) != null) {
                    if (isLikelyException(nextLine)) {
                        firstLine = nextLine;
                        break;
                    }
                    if (nextLine.trim().startsWith("at ")) {
                        reader.reset();
                        break;
                    }
                }
            }

            String exceptionType = "java.lang.Throwable";
            String message = "";
            List<StackTraceFrame> currentFrames = new ArrayList<>();

            if (firstLine.trim().startsWith("at ")) {
                exceptionType = "java.lang.Throwable";
                message = "";
                Matcher atMatcher = AT_LINE_PATTERN.matcher(firstLine.trim());
                if (atMatcher.find()) {
                    currentFrames.add(StackTraceFrame.of(atMatcher.group(1), atMatcher.group(2),
                            atMatcher.group(3) != null ? atMatcher.group(3) : "Unknown",
                            atMatcher.group(4) != null ? Integer.parseInt(atMatcher.group(4)) : -1));
                }
            } else {
                Matcher headerMatcher = HEADER_PATTERN.matcher(firstLine.trim());
                if (headerMatcher.find()) {
                    exceptionType = headerMatcher.group(1);
                    message = headerMatcher.group(2) != null ? headerMatcher.group(2).trim() : "";
                } else {
                    int colonIdx = firstLine.indexOf(':');
                    if (colonIdx > 0 && firstLine.substring(0, colonIdx).contains(".")) {
                        exceptionType = firstLine.substring(0, colonIdx).trim();
                        message = firstLine.substring(colonIdx + 1).trim();
                    } else {
                        exceptionType = firstLine.trim();
                    }
                }
            }

            List<RawExceptionBlock> blocks = new ArrayList<>();
            blocks.add(new RawExceptionBlock(exceptionType, message, currentFrames));

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("...")) {
                    continue;
                }

                Matcher causedByMatcher = CAUSED_BY_PATTERN.matcher(trimmed);
                if (causedByMatcher.find()) {
                    String causeType = causedByMatcher.group(1);
                    String causeMsg = causedByMatcher.group(2) != null ? causedByMatcher.group(2).trim() : "";
                    currentFrames = new ArrayList<>();
                    blocks.add(new RawExceptionBlock(causeType, causeMsg, currentFrames));
                    continue;
                }

                Matcher atMatcher = AT_LINE_PATTERN.matcher(trimmed);
                if (atMatcher.find()) {
                    String declaringClass = atMatcher.group(1);
                    String methodName = atMatcher.group(2);
                    String fileName = atMatcher.group(3) != null ? atMatcher.group(3) : "Unknown";
                    int lineNumber = atMatcher.group(4) != null ? Integer.parseInt(atMatcher.group(4)) : -1;

                    currentFrames.add(StackTraceFrame.of(declaringClass, methodName, fileName, lineNumber));
                }
            }

            // Chain blocks together backwards: last block is root cause
            ParsedException currentCause = null;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                RawExceptionBlock block = blocks.get(i);
                currentCause = new ParsedException(block.type, block.message, block.frames, currentCause);
            }

            return Optional.ofNullable(currentCause);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean isLikelyException(String line) {
        if (line == null || line.isBlank()) return false;
        String trimmed = line.trim();
        Matcher m = HEADER_PATTERN.matcher(trimmed);
        if (m.find()) return true;
        int colon = trimmed.indexOf(':');
        String token = colon > 0 ? trimmed.substring(0, colon).trim() : trimmed;
        return token.contains(".") && (token.endsWith("Exception") || token.endsWith("Error") || token.endsWith("Throwable"));
    }

    private record RawExceptionBlock(String type, String message, List<StackTraceFrame> frames) {}
}
