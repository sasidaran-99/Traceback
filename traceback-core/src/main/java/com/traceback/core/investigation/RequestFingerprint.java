package com.traceback.core.investigation;

import com.traceback.core.security.SecretRedactor;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.stacktrace.StackTraceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic request fingerprinting utility for TraceBack investigations.
 *
 * Computes a collision-resistant SHA-256 fingerprint using normalized, unambiguous
 * TLV (Tag-Length-Value) length-prefixed encoding across non-volatile request attributes.
 *
 * Normalization Rules:
 * - Canonical Repository Path: Normalized separators, trimmed, lowercased, path traversal resolved.
 * - Service Name & Environment: Trimmed, lowercased, defaults applied.
 * - Investigation Mode: Standard enum string.
 * - Exception Signature: Root-cause exception class name with volatile memory addresses, UUIDs,
 *   and object hashes scrubbed.
 * - Stack Trace Signature: Normalized stack frames preserving class, method, file, and failure line numbers.
 * - Log Content Hash: SHA-256 of log body with ISO/standard timestamp prefixes and thread IDs scrubbed.
 * - Time Window & Commits: Canonical integer values.
 *
 * Strict Exclusions:
 * - Volatile request timestamps, wall-clock start times, correlation IDs, credentials, and transient metadata.
 */
public final class RequestFingerprint {

    private static final Logger log = LoggerFactory.getLogger(RequestFingerprint.class);
    private static final StackTraceParser STACK_PARSER = new StackTraceParser();

    // Regex for scrubbing volatile hex memory addresses and UUIDs from exception messages
    private static final Pattern MEMORY_ADDR_PATTERN = Pattern.compile("@[0-9a-fA-F]{6,16}|0x[0-9a-fA-F]{6,16}");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    // Regex for scrubbing timestamp prefixes from log lines
    private static final Pattern LOG_TIMESTAMP_PATTERN = Pattern.compile("(?m)^(?:\\[?[0-9]{4}[-/][0-9]{2}[-/][0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}(?:[\\.,][0-9]{1,9})?(?:Z|[+-][0-9]{2}:?[0-9]{2})?\\]?\\s*|\\d{10,13}\\s+)");

    private RequestFingerprint() {
        // Utility class
    }

    /**
     * Computes the 64-character hexadecimal SHA-256 fingerprint for the given investigation request attributes.
     */
    public static String compute(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits,
            InvestigationMode mode
    ) {
        String canonicalPayload = buildCanonicalRepresentation(
                repositoryPath, stackTrace, logFilePath, logContent,
                serviceName, environment, windowMinutes, maxCommits, mode
        );

        return sha256Hex(canonicalPayload);
    }

    /**
     * Builds the deterministic, unambiguous length-prefixed serialization string.
     * Each field uses the format: TAG:length:value\n
     */
    public static String buildCanonicalRepresentation(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits,
            InvestigationMode mode
    ) {
        String normRepo = normalizeRepositoryPath(repositoryPath);
        String normSvc = normalizeServiceName(serviceName);
        String normEnv = normalizeEnvironment(environment);
        String normMode = (mode != null ? mode : InvestigationMode.RUNTIME_FAILURE).name();

        String normExType = "";
        String normExMsg = "";
        String normStackSig = "";

        if (stackTrace != null && !stackTrace.isBlank()) {
            Optional<ParsedException> parsedOpt = STACK_PARSER.parse(stackTrace);
            if (parsedOpt.isPresent()) {
                ParsedException parsed = parsedOpt.get();
                ParsedException root = parsed.getRootCause();
                normExType = normalizeExceptionType(root.exceptionType());
                normExMsg = normalizeExceptionMessage(root.message());
                normStackSig = buildNormalizedStackSignature(root.frames());
            } else {
                // Fallback for unparsed stack text
                normExType = normalizeExceptionType(extractFirstLineType(stackTrace));
                normExMsg = "";
                normStackSig = normalizeRawStackTrace(stackTrace);
            }
        }

        String normLogHash = normalizeLogContent(logFilePath, logContent);
        int normWindow = windowMinutes > 0 ? windowMinutes : 60;
        int normCommits = maxCommits > 0 ? maxCommits : 50;

        StringBuilder sb = new StringBuilder(512);
        appendField(sb, "REPO", normRepo);
        appendField(sb, "SVC", normSvc);
        appendField(sb, "ENV", normEnv);
        appendField(sb, "MODE", normMode);
        appendField(sb, "EX_TYPE", normExType);
        appendField(sb, "EX_MSG", normExMsg);
        appendField(sb, "STACK_SIG", normStackSig);
        appendField(sb, "LOG_HASH", normLogHash);
        appendField(sb, "WINDOW", String.valueOf(normWindow));
        appendField(sb, "COMMITS", String.valueOf(normCommits));

        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String tag, String value) {
        String val = value != null ? value : "";
        sb.append(tag)
          .append(':')
          .append(val.length())
          .append(':')
          .append(val)
          .append('\n');
    }

    public static String normalizeRepositoryPath(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            return "";
        }
        return normalizePathString(repoPath.trim());
    }

    private static String normalizePathString(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }

        // Standardize slashes
        String normalized = rawPath.replace('\\', '/');

        // Check if Windows path (e.g. "C:/...", "c:...", "//server/share" or "\\server\share")
        boolean isWindowsDrive = normalized.matches("^[a-zA-Z]:.*");
        boolean isUnc = normalized.startsWith("//");

        String prefix = "";
        String pathBody = normalized;

        if (isWindowsDrive) {
            // e.g. "C:/foo/bar" -> prefix = "c:", pathBody = "/foo/bar"
            char drive = Character.toLowerCase(normalized.charAt(0));
            prefix = drive + ":";
            pathBody = normalized.substring(2);
            if (!pathBody.startsWith("/") && !pathBody.isEmpty()) {
                pathBody = "/" + pathBody;
            }
        } else if (isUnc) {
            prefix = "//";
            pathBody = normalized.substring(2);
        }

        boolean isAbsolute = isWindowsDrive || isUnc || pathBody.startsWith("/");

        // Tokenize and resolve '.' and '..'
        String[] parts = pathBody.split("/+");
        java.util.List<String> resolved = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!resolved.isEmpty()) {
                    String last = resolved.get(resolved.size() - 1);
                    if (!"..".equals(last)) {
                        resolved.remove(resolved.size() - 1);
                    } else if (!isAbsolute) {
                        resolved.add("..");
                    }
                } else if (!isAbsolute) {
                    resolved.add("..");
                }
            } else {
                resolved.add(part);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        if (isAbsolute && !isUnc && !prefix.endsWith("/")) {
            sb.append('/');
        }
        sb.append(String.join("/", resolved));

        String result = sb.toString();
        if (result.isEmpty() && isAbsolute) {
            result = "/";
        }

        // Case normalization:
        // Windows drive paths and UNC paths are case-insensitive on Windows -> lowercase.
        // Linux/POSIX paths (and relative paths without Windows drive) preserve case!
        if (isWindowsDrive || isUnc) {
            return result.toLowerCase(Locale.ROOT);
        } else {
            return result;
        }
    }

    private static String normalizeServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return "default-service";
        }
        return serviceName.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "production";
        }
        return environment.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeExceptionType(String exType) {
        if (exType == null || exType.isBlank()) {
            return "";
        }
        String scrubbed = MEMORY_ADDR_PATTERN.matcher(exType.trim()).replaceAll("@<ADDR>");
        return UUID_PATTERN.matcher(scrubbed).replaceAll("<UUID>");
    }

    private static String normalizeExceptionMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return "";
        }
        String redacted = SecretRedactor.redactText(msg.trim());
        String scrubbed = MEMORY_ADDR_PATTERN.matcher(redacted).replaceAll("@<ADDR>");
        return UUID_PATTERN.matcher(scrubbed).replaceAll("<UUID>");
    }

    private static String buildNormalizedStackSignature(List<StackTraceFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // Include up to top 10 meaningful frames preserving class, method, file, and line number
        int count = 0;
        for (StackTraceFrame frame : frames) {
            if (count++ >= 10) break;
            sb.append(frame.declaringClass())
              .append('#')
              .append(frame.methodName())
              .append('(')
              .append(frame.fileName() != null ? frame.fileName() : "")
              .append(':')
              .append(frame.lineNumber())
              .append(';')
              .append('\n');
        }
        return sb.toString();
    }

    private static String normalizeRawStackTrace(String raw) {
        String redacted = SecretRedactor.redactText(raw);
        String scrubbed = MEMORY_ADDR_PATTERN.matcher(redacted).replaceAll("@<ADDR>");
        return scrubbed.replaceAll("\\r?\\n\\s+", "\n").trim();
    }

    private static String extractFirstLineType(String stack) {
        String firstLine = stack.lines().findFirst().orElse("").trim();
        int colonIdx = firstLine.indexOf(':');
        return colonIdx > 0 ? firstLine.substring(0, colonIdx).trim() : firstLine;
    }

    private static String normalizeLogContent(String logFilePath, String logContent) {
        if (logContent != null && !logContent.isBlank()) {
            String redacted = SecretRedactor.redactText(logContent);
            String strippedTimestamps = LOG_TIMESTAMP_PATTERN.matcher(redacted).replaceAll("");
            String normalizedLines = strippedTimestamps.replaceAll("\\r?\\n\\s+", "\n").trim();
            return sha256Hex(normalizedLines);
        }
        if (logFilePath != null && !logFilePath.isBlank()) {
            return "FILE:" + normalizeRepositoryPath(logFilePath);
        }
        return "";
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
