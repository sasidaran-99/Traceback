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
 * Adapter for parsing Node.js, TypeScript, and V8-based JavaScript stack traces.
 */
@Component
public class NodeStackTraceAdapter implements StackTraceAdapter {

    private static final Pattern NODE_HEADER_PATTERN = Pattern.compile(
            "^(?:(?:\\[cause\\]:|Caused by:)\\s*)?([a-zA-Z_$][a-zA-Z0-9_$.]*)(?::\\s*(.*))?$"
    );

    private static final Pattern NODE_FRAME_PATTERN = Pattern.compile(
            "^\\s*at\\s+(?:(?:([^\t\r\n(]+)\\s+\\(([^:()]+):(\\d+)(?::\\d+)?\\))|([^:()]+):(\\d+)(?::\\d+)?)$"
    );

    @Override
    public String getSupportedLanguage() {
        return "NodeJS";
    }

    @Override
    public boolean canParse(String rawStackTrace) {
        if (rawStackTrace == null || rawStackTrace.isBlank()) {
            return false;
        }
        // Exclude Python
        if (rawStackTrace.contains("Traceback (most recent call last):")
                || (rawStackTrace.contains("File \"") && rawStackTrace.contains(", line "))) {
            return false;
        }

        // Match Node characteristics: .js:, .ts:, .jsx:, .tsx:, or Node standard error names
        boolean hasNodeFileRef = rawStackTrace.contains(".js:")
                || rawStackTrace.contains(".ts:")
                || rawStackTrace.contains(".mjs:")
                || rawStackTrace.contains(".cjs:")
                || rawStackTrace.contains(".tsx:")
                || rawStackTrace.contains(".jsx:")
                || rawStackTrace.contains("node:net:")
                || rawStackTrace.contains("node:internal");

        boolean hasJsErrorType = rawStackTrace.contains("TypeError:")
                || rawStackTrace.contains("ReferenceError:")
                || rawStackTrace.contains("RangeError:")
                || rawStackTrace.contains("SyntaxError:")
                || rawStackTrace.contains("URIError:");

        return (hasNodeFileRef || hasJsErrorType) && (rawStackTrace.contains("    at ") || rawStackTrace.contains("\tat "));
    }

    @Override
    public Optional<ParsedException> parse(String rawStackTrace) {
        if (rawStackTrace == null || rawStackTrace.isBlank()) {
            return Optional.empty();
        }

        try (BufferedReader reader = new BufferedReader(new StringReader(rawStackTrace.trim()))) {
            List<NodeBlock> blocks = new ArrayList<>();
            List<StackTraceFrame> currentFrames = new ArrayList<>();
            String currentType = "Error";
            String currentMsg = "";

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("[cause]:") || trimmed.startsWith("Caused by:")) {
                    if (!currentFrames.isEmpty()) {
                        blocks.add(new NodeBlock(currentType, currentMsg, currentFrames));
                        currentFrames = new ArrayList<>();
                    }
                    String causeHeader = trimmed.replaceFirst("^(?:\\[cause\\]:|Caused by:)\\s*", "");
                    Matcher m = NODE_HEADER_PATTERN.matcher(causeHeader);
                    if (m.find()) {
                        currentType = m.group(1);
                        currentMsg = m.group(2) != null ? m.group(2).trim() : "";
                    } else {
                        currentType = "Error";
                        currentMsg = causeHeader;
                    }
                    continue;
                }

                Matcher frameMatcher = NODE_FRAME_PATTERN.matcher(trimmed);
                if (frameMatcher.find()) {
                    String funcInfo = frameMatcher.group(1);
                    String filePath = frameMatcher.group(2);
                    String lineNumStr = frameMatcher.group(3);

                    if (filePath == null) {
                        filePath = frameMatcher.group(4);
                        lineNumStr = frameMatcher.group(5);
                    }

                    int lineNumber = lineNumStr != null ? Integer.parseInt(lineNumStr) : -1;
                    String fileName = extractFileName(filePath);
                    String declaringClass = extractDeclaringClass(funcInfo, fileName);
                    String methodName = extractMethodName(funcInfo);

                    currentFrames.add(StackTraceFrame.of(declaringClass, methodName, fileName, lineNumber));
                    continue;
                }

                // If not a frame line and blocks are empty, might be primary header
                if (currentFrames.isEmpty() && blocks.isEmpty()) {
                    Matcher headerMatcher = NODE_HEADER_PATTERN.matcher(trimmed);
                    if (headerMatcher.find()) {
                        currentType = headerMatcher.group(1);
                        currentMsg = headerMatcher.group(2) != null ? headerMatcher.group(2).trim() : "";
                    }
                }
            }

            if (!currentFrames.isEmpty()) {
                blocks.add(new NodeBlock(currentType, currentMsg, currentFrames));
            }

            if (blocks.isEmpty()) {
                return Optional.empty();
            }

            // Chain blocks backwards: last block is root cause
            ParsedException currentCause = null;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                NodeBlock b = blocks.get(i);
                currentCause = new ParsedException(b.type(), b.message(), b.frames(), currentCause);
            }

            return Optional.ofNullable(currentCause);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String extractFileName(String fullPath) {
        if (fullPath == null) return "index.js";
        int lastSlash = Math.max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'));
        return lastSlash >= 0 ? fullPath.substring(lastSlash + 1) : fullPath;
    }

    private String extractDeclaringClass(String funcInfo, String fileName) {
        if (funcInfo == null || funcInfo.isBlank()) {
            return fileName;
        }
        int dot = funcInfo.indexOf('.');
        if (dot > 0) {
            return funcInfo.substring(0, dot).trim();
        }
        return fileName;
    }

    private String extractMethodName(String funcInfo) {
        if (funcInfo == null || funcInfo.isBlank()) {
            return "<anonymous>";
        }
        int dot = funcInfo.lastIndexOf('.');
        if (dot > 0 && dot < funcInfo.length() - 1) {
            return funcInfo.substring(dot + 1).trim();
        }
        return funcInfo.trim();
    }

    private record NodeBlock(String type, String message, List<StackTraceFrame> frames) {}
}
