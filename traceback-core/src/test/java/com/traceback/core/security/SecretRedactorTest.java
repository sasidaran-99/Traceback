package com.traceback.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    private final SecretRedactor redactor = SecretRedactor.getInstance();

    @Test
    @DisplayName("Should detect sensitive configuration key stems")
    void shouldDetectSensitiveKeys() {
        assertThat(redactor.isSensitiveKey("spring.datasource.password")).isTrue();
        assertThat(redactor.isSensitiveKey("app.security.jwt.secret")).isTrue();
        assertThat(redactor.isSensitiveKey("openai.api-key")).isTrue();
        assertThat(redactor.isSensitiveKey("aws.access_key")).isTrue();
        assertThat(redactor.isSensitiveKey("db.passwd")).isTrue();
        assertThat(redactor.isSensitiveKey("auth_token")).isTrue();
        assertThat(redactor.isSensitiveKey("client_secret")).isTrue();
    }

    @Test
    @DisplayName("Should preserve allowlisted keys even if containing partial sensitive substrings")
    void shouldPreserveAllowlistedKeys() {
        assertThat(redactor.isSensitiveKey("public_key")).isFalse();
        assertThat(redactor.isSensitiveKey("keyword")).isFalse();
        assertThat(redactor.isSensitiveKey("keyboard")).isFalse();
        assertThat(redactor.isSensitiveKey("monkey")).isFalse();
        assertThat(redactor.isSensitiveKey("server.port")).isFalse();
        assertThat(redactor.isSensitiveKey("spring.application.name")).isFalse();
    }

    @Test
    @DisplayName("Should redact private key PEM blocks")
    void shouldRedactPrivateKeyBlocks() {
        String input = """
                Error during TLS handshake:
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA0fakePrivateKeyPayload1234567890abcdefghijklmnopqrstuvwxyz
                -----END RSA PRIVATE KEY-----
                Failed to bind socket.
                """;
        String redacted = redactor.redact(input);
        assertThat(redacted).doesNotContain("fakePrivateKeyPayload");
        assertThat(redacted).contains("[REDACTED PRIVATE KEY]");
        assertThat(redacted).contains("Failed to bind socket.");
    }

    @Test
    @DisplayName("Should redact JWT tokens")
    void shouldRedactJwtTokens() {
        String fakeJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String text = "User login failed with token: " + fakeJwt + " expired.";
        String redacted = redactor.redact(text);

        assertThat(redacted).doesNotContain(fakeJwt);
        assertThat(redacted).contains("[REDACTED JWT]");
        assertThat(redacted).contains("expired.");
    }

    @Test
    @DisplayName("Should redact common API keys")
    void shouldRedactApiKeys() {
        String text = "OpenAI key: sk-mockTestFakeApiKey1234567890abcdef, GitHub: ghp_mockFakeGitHubToken1234567890abcdef12, AWS: AKIAIOSFODNN7EXAMPLE";
        String redacted = redactor.redact(text);

        assertThat(redacted).doesNotContain("sk-mockTestFakeApiKey1234567890abcdef");
        assertThat(redacted).doesNotContain("ghp_mockFakeGitHubToken1234567890abcdef12");
        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    @DisplayName("Should redact credentials embedded in connection URLs")
    void shouldRedactUrlCredentials() {
        String dbUrl = "Connecting to postgresql://admin_user:secret_fake_pass_987@db.internal:5432/production_db";
        String redacted = redactor.redact(dbUrl);

        assertThat(redacted).doesNotContain("secret_fake_pass_987");
        assertThat(redacted).contains("postgresql://admin_user:[REDACTED]@db.internal:5432/production_db");
    }

    @Test
    @DisplayName("Should redact Bearer authorization header tokens")
    void shouldRedactBearerTokens() {
        String header = "Headers: Authorization: Bearer mockFakeBearerToken1234567890abcdef and Content-Type: application/json";
        String redacted = redactor.redact(header);

        assertThat(redacted).doesNotContain("mockFakeBearerToken1234567890abcdef");
        assertThat(redacted).contains("Bearer [REDACTED]");
    }

    @Test
    @DisplayName("Should redact inline key=value secret patterns")
    void shouldRedactInlineKeyValueSecrets() {
        String logLine = "Failed auth: password=superSecretPassword123 and user=alice";
        String redacted = redactor.redact(logLine);

        assertThat(redacted).doesNotContain("superSecretPassword123");
        assertThat(redacted).contains("password=[REDACTED]");
        assertThat(redacted).contains("user=alice");
    }

    @Test
    @DisplayName("Should sanitize config change map while preserving change deltas and non-sensitive keys")
    void shouldSanitizeConfigMap() {
        Map<String, String> configMap = Map.of(
                "server.port", "8080 => 8081",
                "spring.datasource.password", "oldFakePass => newFakePass",
                "auth.secret", "<none> => someSecretValue"
        );

        Map<String, String> redacted = redactor.redactConfigMap(configMap);

        assertThat(redacted.get("server.port")).isEqualTo("8080 => 8081");
        assertThat(redacted.get("spring.datasource.password")).isEqualTo("[REDACTED] => [REDACTED]");
        assertThat(redacted.get("auth.secret")).isEqualTo("<none> => [REDACTED]");
    }

    @Test
    @DisplayName("Should sanitize nested metadata structures")
    void shouldSanitizeMetadata() {
        Map<String, Object> metadata = Map.of(
                "author", "developer@company.com",
                "apiKey", "fakeSecretKey999",
                "nested", Map.of(
                        "password", "nestedSecretPass",
                        "database", "users_db"
                )
        );

        Map<String, Object> sanitized = redactor.redactMetadata(metadata);

        assertThat(sanitized.get("author")).isEqualTo("developer@company.com");
        assertThat(sanitized.get("apiKey")).isEqualTo("[REDACTED]");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("nested");
        assertThat(nested.get("password")).isEqualTo("[REDACTED]");
        assertThat(nested.get("database")).isEqualTo("users_db");
    }
}
