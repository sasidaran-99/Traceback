package com.traceback.core.stacktrace;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for parsing Python tracebacks, including single and chained exceptions
 * (e.g., "During handling of the above exception..." or "The above exception was the direct cause...").
 */
@Component
public class PythonStackTraceAdapter implements StackTraceAdapter {

    private static final Pattern PYTHON_FRAME_PATTERN = Pattern.compile(
            "^\\s*File\\s+\"([^\"]+)\",\\s+line\\s+(\\d+)(?:,\\s+in\\s+(.*))?$"
    );

    private static final Pattern PYTHON_EXCEPTION_PATTERN = Pattern.compile(
            "^([a-zA-Z_][a-zA-Z0-9_.]*(?:Error|Exception|Warning|Interrupt|Exit|Fault|Problem|[A-Z][a-zA-Z0-9_]*))(?::\\s*(.*))?$"
    );

    @Override
    public String getSupportedLanguage() {
        return "Python";
    }

    @Override
    public boolean canParse(String rawStackTrace) {
        if (rawStackTrace == null || rawStackTrace.isBlank()) {
            return false;
        }
        return rawStackTrace.contains("Traceback (most recent call last):")
                || (rawStackTrace.contains("File \"") && rawStackTrace.contains(", line "));
    }

    @Override
    public Optional<ParsedException> parse(String rawStackTrace) {
        if (rawStackTrace == null || rawStackTrace.isBlank()) {
            return Optional.empty();
        }

        try (BufferedReader reader = new BufferedReader(new StringReader(rawStackTrace.trim()))) {
            List<PythonBlock> blocks = new ArrayList<>();
            List<StackTraceFrame> currentFrames = new ArrayList<>();
            String currentType = null;
            String currentMsg = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("Traceback (most recent call last):")) {
                    if (currentType != null) {
                        blocks.add(new PythonBlock(currentType, currentMsg != null ? currentMsg : "", currentFrames));
                        currentFrames = new ArrayList<>();
                        currentType = null;
                        currentMsg = null;
                    }
                    continue;
                }

                if (trimmed.startsWith("During handling of the above exception")
                        || trimmed.startsWith("The above exception was the direct cause")) {
                    if (currentType != null) {
                        blocks.add(new PythonBlock(currentType, currentMsg != null ? currentMsg : "", currentFrames));
                        currentFrames = new ArrayList<>();
                        currentType = null;
                        currentMsg = null;
                    }
                    continue;
                }

                Matcher frameMatcher = PYTHON_FRAME_PATTERN.matcher(trimmed);
                if (frameMatcher.find()) {
                    String fullPath = frameMatcher.group(1);
                    int lineNum = Integer.parseInt(frameMatcher.group(2));
                    String funcName = frameMatcher.group(3) != null ? frameMatcher.group(3).trim() : "<module>";

                    String fileName = extractFileName(fullPath);
                    String declaringClass = extractDeclaringClass(fullPath);

                    currentFrames.add(StackTraceFrame.of(declaringClass, funcName, fileName, lineNum));
                    continue;
                }

                // If not inside a traceback header and not a code line snippet (which is indented under File line)
                if (!currentFrames.isEmpty() && !trimmed.startsWith("File ") && !line.startsWith("    ")) {
                    Matcher excMatcher = PYTHON_EXCEPTION_PATTERN.matcher(trimmed);
                    if (excMatcher.find()) {
                        currentType = excMatcher.group(1);
                        currentMsg = excMatcher.group(2) != null ? excMatcher.group(2).trim() : "";
                    }
                }
            }

            if (currentType != null) {
                blocks.add(new PythonBlock(currentType, currentMsg != null ? currentMsg : "", currentFrames));
            } else if (!currentFrames.isEmpty()) {
                blocks.add(new PythonBlock("PythonException", "", currentFrames));
            }

            if (blocks.isEmpty()) {
                return Optional.empty();
            }

            // In Python chained exceptions, the first block printed is the root cause,
            // and the last block printed is the outer wrapper.
            // In ParsedException: outer.cause() points to the underlying cause.
            ParsedException result = null;
            for (PythonBlock block : blocks) {
                result = new ParsedException(block.type(), block.message(), block.frames(), result);
            }

            return Optional.ofNullable(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String extractFileName(String fullPath) {
        if (fullPath == null) return "Unknown.py";
        int lastSlash = Math.max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'));
        return lastSlash >= 0 ? fullPath.substring(lastSlash + 1) : fullPath;
    }

    private String extractDeclaringClass(String fullPath) {
        String fileName = extractFileName(fullPath);
        if (fileName.endsWith(".py")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    private record PythonBlock(String type, String message, List<StackTraceFrame> frames) {}
}
