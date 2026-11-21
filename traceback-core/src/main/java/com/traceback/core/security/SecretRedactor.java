package com.traceback.core.security;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal, reusable security service for redacting sensitive secrets, credentials,
 * API keys, and tokens before evidence is registered, persisted, or passed to external AI services.
 */
@Component
public class SecretRedactor {

    public static final String REDACTED_MARKER = "[REDACTED]";
    public static final String REDACTED_KEY_MARKER = "[REDACTED PRIVATE KEY]";
    public static final String REDACTED_JWT_MARKER = "[REDACTED JWT]";

    // Sensitive key names that indicate credential payloads
    private static final Set<String> SENSITIVE_KEY_STEMS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
            "api-key", "access_key", "access-key", "private_key", "private-key",
            "client_secret", "client-secret", "credential", "jwt",
            "bearer", "passphrase", "signature", "salt", "db_pass", "db-pass",
            "auth_token", "auth-token", "authorization", "authentication"
    );

    // Innocent terms that contain sensitive stems but should not be redacted
    private static final Set<String> ALLOWLISTED_KEY_TERMS = Set.of(
            "public_key", "public-key", "publickey", "keyword", "keyboard", "monkey",
            "author", "authors", "authority"
    );

    // Private key blocks (PEM format)
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9_-]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z0-9_-]+ )?PRIVATE KEY-----"
    );

    // JWT token pattern (three base64 segments starting with eyJ)
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\b(ey[A-Za-z0-9_-]{10,}\\.ey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b"
    );

    // Common API key patterns
    private static final Pattern API_KEYS_PATTERN = Pattern.compile(
            "\\b(sk-[a-zA-Z0-9_-]{20,}|gh[pousr]_[a-zA-Z0-9]{36,}|glpat-[a-zA-Z0-9_-]{20,}|xox[baprs]-[a-zA-Z0-9-]{10,}|AIza[0-9A-Za-z-_]{35}|AKIA[0-9A-Z]{16})\\b"
    );

    // Credentials embedded in connection URLs (http, https, jdbc, postgres, mysql, redis, amqp)
    private static final Pattern URL_CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://[^:\\s/@]+):([^@\\s/]+)@"
    );

    // HTTP Authorization Bearer tokens
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\b(bearer\\s+)([a-zA-Z0-9._~+/-]{16,})=*",
            Pattern.CASE_INSENSITIVE
    );

    // Generic key=value or key: value secret patterns in logs and text (avoids double-redacting [REDACTED...])
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api_?key|access_?key|private_?key|client_?secret|credential|auth_?token)\\b\\s*([:=])\\s*([\"']?)(?!\\[REDACTED)([^\"'\\r\\n\\s]+)\\3"
    );

    private static final SecretRedactor INSTANCE = new SecretRedactor();

    public static SecretRedactor getInstance() {
        return INSTANCE;
    }

    public static String redactText(String text) {
        return INSTANCE.redact(text);
    }

    /**
     * Determines whether a configuration or metadata key represents sensitive data.
     */
    public boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);

        for (String allow : ALLOWLISTED_KEY_TERMS) {
            if (lower.equals(allow) || lower.startsWith("author")) {
                return false;
            }
        }

        if (lower.equals("auth") || lower.startsWith("auth.") || lower.startsWith("auth_") || lower.endsWith(".auth") || lower.endsWith("_auth")) {
            return true;
        }

        for (String stem : SENSITIVE_KEY_STEMS) {
            if (lower.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a specific configuration key and value pair.
     * Preserves key name and non-sensitive structural details.
     */
    public String redactConfigValue(String key, String value) {
        if (value == null || value.isBlank() || "<none>".equals(value) || "<deleted>".equals(value)) {
            return value;
        }

        if (isSensitiveKey(key)) {
            return REDACTED_MARKER;
        }

        return redact(value);
    }

    /**
     * Sanitizes a configuration change map (key -> "oldValue => newValue").
     */
    public Map<String, String> redactConfigMap(Map<String, String> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();

            if (isSensitiveKey(key)) {
                if (val != null && val.contains("=>")) {
                    String[] parts = val.split("=>", 2);
                    String oldPart = parts[0].trim();
                    String newPart = parts[1].trim();

                    String sanitizedOld = "<none>".equals(oldPart) ? "<none>" : REDACTED_MARKER;
                    String sanitizedNew = "<deleted>".equals(newPart) ? "<deleted>" : REDACTED_MARKER;
                    redacted.put(key, sanitizedOld + " => " + sanitizedNew);
                } else {
                    redacted.put(key, REDACTED_MARKER);
                }
            } else {
                redacted.put(key, redact(val));
            }
        }
        return Collections.unmodifiableMap(redacted);
    }

    /**
     * Deeply sanitizes an arbitrary metadata dictionary.
     */
    public Map<String, Object> redactMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();

            if (isSensitiveKey(key)) {
                sanitized.put(key, REDACTED_MARKER);
            } else if (val instanceof String s) {
                sanitized.put(key, redact(s));
            } else if (val instanceof Map<?, ?> m) {
                Map<String, Object> nested = new LinkedHashMap<>();
                for (Map.Entry<?, ?> ne : m.entrySet()) {
                    if (ne.getKey() != null) {
                        String nk = ne.getKey().toString();
                        if (isSensitiveKey(nk)) {
                            nested.put(nk, REDACTED_MARKER);
                        } else if (ne.getValue() instanceof String ns) {
                            nested.put(nk, redact(ns));
                        } else {
                            nested.put(nk, ne.getValue());
                        }
                    }
                }
                sanitized.put(key, nested);
            } else {
                sanitized.put(key, val);
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    /**
     * Sanitizes free-form text by redacting private keys, JWTs, API tokens,
     * URL-embedded credentials, and inline key=value secret pairs.
     */
    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // 1. Redact Private Key blocks
        String result = PRIVATE_KEY_PATTERN.matcher(text).replaceAll(REDACTED_KEY_MARKER);

        // 2. Redact embedded URL credentials: scheme://user:password@host -> scheme://user:[REDACTED]@host
        result = URL_CREDENTIAL_PATTERN.matcher(result).replaceAll("$1:" + REDACTED_MARKER + "@");

        // 3. Redact Bearer authorization tokens
        result = BEARER_PATTERN.matcher(result).replaceAll("$1" + REDACTED_MARKER);

        // 4. Redact JWT tokens
        result = JWT_PATTERN.matcher(result).replaceAll(REDACTED_JWT_MARKER);

        // 5. Redact known API key patterns (OpenAI, GitHub, GitLab, Slack, Google, AWS)
        result = API_KEYS_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);

        // 6. Redact inline key=value or key: value secrets in text
        Matcher kvMatcher = KEY_VALUE_SECRET_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (kvMatcher.find()) {
            String keyName = kvMatcher.group(1);
            String sep = kvMatcher.group(2);
            kvMatcher.appendReplacement(sb, Matcher.quoteReplacement(keyName + sep + REDACTED_MARKER));
        }
        kvMatcher.appendTail(sb);

        return sb.toString();
    }
}
