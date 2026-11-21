package com.traceback.core.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes multiline application and system logs to identify errors, extract signatures,
 * and determine failure onset timestamps.
 */
@Component
public class LogAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(LogAnalyzer.class);

    // Matches standard Spring Boot Logback: 2026-10-10 14:16:30.123 ERROR 12345 --- [thread] logger : message
    // Also matches ISO timestamps: 2026-10-10T14:16:30.123Z or 2026-10-10T14:16:30.123+00:00
    private static final Pattern SPRING_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3,6})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+([A-Z]+)\\s+(?:\\d+\\s+---\\s+)?(?:\\[([^\\]]+)\\]\\s+)?([\\w$.]+)\\s*[-:]\\s*(.*)$"
    );

    // Matches Log4j / Logback thread-first format: 2026-11-07 14:15:00.120 [http-nio-8080-exec-1] WARN com.zaxxer... - message
    private static final Pattern THREAD_FIRST_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3,6})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+\\[([^\\]]+)\\]\\s+([A-Z]+)\\s+([\\w$.]+)\\s*[-:]\\s*(.*)$"
    );

    // Matches Python logging format: 2026-11-08 14:15:00,123 - root - ERROR - message
    private static final Pattern PYTHON_DASH_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3,6})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s*-\\s*([\\w$.]+)\\s*-\\s*([A-Z]+)\\s*-\\s*(.*)$"
    );

    // Matches Node / bracketed format: [2026-11-08T14:15:00.123Z] [ERROR] [logger] message
    private static final Pattern BRACKETED_LOG_PATTERN = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3,6})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\]\\s+\\[([A-Z]+)\\](?:\\s+\\[([^\\]]+)\\])?\\s*[-:]?\\s*(.*)$"
    );

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter SPACE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter SPACE_COMMA_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
    private static final DateTimeFormatter SPACE_SEC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StackTraceParser stackTraceParser;
    private final ObjectMapper objectMapper;

    public LogAnalyzer(StackTraceParser stackTraceParser, ObjectMapper objectMapper) {
        this.stackTraceParser = stackTraceParser;
        this.objectMapper = objectMapper;
    }

    public LogAnalyzer() {
        this(new StackTraceParser(), new ObjectMapper());
    }

    /**
     * Analyzes logs read from a filesystem path.
     */
    public LogEvidence analyzeFile(Path logFilePath) throws IOException {
        if (!Files.exists(logFilePath)) {
            throw new IllegalArgumentException("Log file does not exist: " + logFilePath);
        }
        long size = Files.size(logFilePath);
        if (size > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("Log file exceeds maximum allowed size of 5MB: " + size + " bytes");
        }
        String content = Files.readString(logFilePath);
        return analyzeContent(content);
    }

    /**
     * Analyzes raw log content string.
     */
    public LogEvidence analyzeContent(String logContent) {
        if (logContent == null || logContent.isBlank()) {
            return new LogEvidence(null, null, 0, 0, Collections.emptyList(), Collections.emptyMap(), null, null);
        }

        List<LogEntry> allEntries = parseLogLines(logContent);
        List<LogEntry> errorEntries = new ArrayList<>();
        int totalWarnings = 0;

        for (LogEntry entry : allEntries) {
            if (entry.isError()) {
                errorEntries.add(entry);
            } else if (entry.isWarn()) {
                totalWarnings++;
            }
        }

        if (errorEntries.isEmpty()) {
            return new LogEvidence(null, null, 0, totalWarnings, Collections.emptyList(), Collections.emptyMap(), null, null);
        }

        // Determine chronological boundaries safely with null-aware comparator
        errorEntries.sort(Comparator.comparing(LogEntry::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        Instant firstFailure = errorEntries.stream()
                .map(LogEntry::timestamp)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Instant lastFailure = errorEntries.stream()
                .map(LogEntry::timestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // Group errors by signature
        Map<ErrorSignature, List<LogEntry>> grouped = new LinkedHashMap<>();
        for (LogEntry entry : errorEntries) {
            ErrorSignature sig = deriveSignature(entry);
            grouped.computeIfAbsent(sig, k -> new ArrayList<>()).add(entry);
        }

        // Find primary signature (highest frequency or first observed)
        ErrorSignature primarySig = grouped.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse(null);

        ParsedException primaryException = null;
        for (LogEntry err : errorEntries) {
            if (err.parsedException() != null) {
                primaryException = err.parsedException();
                break;
            }
        }

        return new LogEvidence(
                firstFailure,
                lastFailure,
                errorEntries.size(),
                totalWarnings,
                errorEntries,
                grouped,
                primarySig,
                primaryException
        );
    }

    private List<LogEntry> parseLogLines(String content) {
        List<LogEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            int lineNumber = 0;

            RawLogBuilder current = null;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Try JSON log line
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    if (current != null) {
                        entries.add(current.build(stackTraceParser));
                        current = null;
                    }
                    parseJsonLine(trimmed, lineNumber).ifPresent(entries::add);
                    continue;
                }

                // Check if line matches a new log entry header (Spring Boot standard or Log4j thread-first)
                Matcher matcher = SPRING_LOG_PATTERN.matcher(trimmed);
                Matcher tfMatcher = THREAD_FIRST_LOG_PATTERN.matcher(trimmed);
                Matcher pyMatcher = PYTHON_DASH_LOG_PATTERN.matcher(trimmed);
                Matcher bracketMatcher = BRACKETED_LOG_PATTERN.matcher(trimmed);

                if (matcher.find()) {
                    if (current != null) {
                        entries.add(current.build(stackTraceParser));
                    }

                    Instant timestamp = parseTimestamp(matcher.group(1));
                    String level = matcher.group(2);
                    String thread = matcher.group(3) != null ? matcher.group(3) : "main";
                    String logger = matcher.group(4);
                    String message = matcher.group(5);

                    current = new RawLogBuilder(timestamp, level, thread, logger, message, lineNumber);
                } else if (tfMatcher.find()) {
                    if (current != null) {
                        entries.add(current.build(stackTraceParser));
                    }

                    Instant timestamp = parseTimestamp(tfMatcher.group(1));
                    String thread = tfMatcher.group(2) != null ? tfMatcher.group(2) : "main";
                    String level = tfMatcher.group(3);
                    String logger = tfMatcher.group(4);
                    String message = tfMatcher.group(5);

                    current = new RawLogBuilder(timestamp, level, thread, logger, message, lineNumber);
                } else if (pyMatcher.find()) {
                    if (current != null) {
                        entries.add(current.build(stackTraceParser));
                    }

                    Instant timestamp = parseTimestamp(pyMatcher.group(1));
                    String logger = pyMatcher.group(2);
                    String level = pyMatcher.group(3);
                    String message = pyMatcher.group(4);

                    current = new RawLogBuilder(timestamp, level, "main", logger, message, lineNumber);
                } else if (bracketMatcher.find()) {
                    if (current != null) {
                        entries.add(current.build(stackTraceParser));
                    }

                    Instant timestamp = parseTimestamp(bracketMatcher.group(1));
                    String level = bracketMatcher.group(2);
                    String logger = bracketMatcher.group(3) != null ? bracketMatcher.group(3) : "app";
                    String message = bracketMatcher.group(4);

                    current = new RawLogBuilder(timestamp, level, "main", logger, message, lineNumber);
                } else {
                    // Multiline continuation (e.g. stack trace frame or extra message lines)
                    if (current != null) {
                        current.appendStackTraceLine(line);
                    }
                }
            }

            if (current != null) {
                entries.add(current.build(stackTraceParser));
            }

        } catch (IOException e) {
            log.error("Failed to parse log content", e);
        }

        return entries;
    }

    private Optional<LogEntry> parseJsonLine(String json, int lineNumber) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String timestampStr = root.path("timestamp").asText(root.path("@timestamp").asText(""));
            Instant timestamp = parseTimestamp(timestampStr);
            String level = root.path("level").asText("INFO");
            String thread = root.path("thread").asText("main");
            String logger = root.path("logger").asText(root.path("logger_name").asText(""));
            String message = root.path("message").asText("");
            String stackTrace = root.path("stack_trace").asText(root.path("exception").asText(""));

            ParsedException pe = stackTrace.isBlank() ? null : stackTraceParser.parse(stackTrace).orElse(null);

            return Optional.of(new LogEntry(timestamp, level, thread, logger, message, stackTrace, pe, lineNumber));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(raw, ISO_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(raw, SPACE_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(raw, SPACE_COMMA_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(raw, SPACE_SEC_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    private ErrorSignature deriveSignature(LogEntry entry) {
        if (entry.parsedException() != null) {
            ParsedException root = entry.parsedException().getRootCause();
            String location = entry.parsedException().getTopFrame()
                    .map(f -> f.fileName() + ":" + f.lineNumber())
                    .orElse(entry.logger());
            return ErrorSignature.of(root.exceptionType(), root.message(), location);
        }

        String msg = entry.message();
        int colon = msg.indexOf(':');
        String type = "ApplicationError";
        if (colon > 0 && msg.substring(0, colon).contains("Exception")) {
            type = msg.substring(0, colon).trim();
            msg = msg.substring(colon + 1).trim();
        }
        return ErrorSignature.of(type, msg, entry.logger());
    }

    private static class RawLogBuilder {
        private final Instant timestamp;
        private final String level;
        private final String thread;
        private final String logger;
        private final String message;
        private final int lineNumber;
        private final StringBuilder stackTraceBuilder = new StringBuilder();

        public RawLogBuilder(Instant timestamp, String level, String thread, String logger, String message, int lineNumber) {
            this.timestamp = timestamp;
            this.level = level;
            this.thread = thread;
            this.logger = logger;
            this.message = message;
            this.lineNumber = lineNumber;
        }

        public void appendStackTraceLine(String line) {
            stackTraceBuilder.append(line).append("\n");
        }

        public LogEntry build(StackTraceParser parser) {
            String stackTrace = stackTraceBuilder.toString().trim();
            ParsedException pe = null;
            if (!stackTrace.isEmpty()) {
                if (stackTrace.startsWith("at ") || message.contains("Exception") || message.contains("Error")) {
                    pe = parser.parse(message + "\n" + stackTrace).orElse(null);
                }
                if (pe == null) {
                    pe = parser.parse(stackTrace).orElse(null);
                }
                if (pe == null) {
                    pe = parser.parse(message + "\n" + stackTrace).orElse(null);
                }
            } else if (message.contains("Exception") || message.contains("Error")) {
                pe = parser.parse(message).orElse(null);
            }
            return new LogEntry(timestamp, level, thread, logger, message, stackTrace, pe, lineNumber);
        }
    }
}
