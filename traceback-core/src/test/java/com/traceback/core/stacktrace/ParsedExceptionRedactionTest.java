package com.traceback.core.stacktrace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedExceptionRedactionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("Should redact passwords, JWTs, API keys, and connection credentials from exception message")
    void shouldRedactSecretsInParsedExceptionMessage() {
        String fakeJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String fakeApiKey = "sk-mockTestApiKey1234567890abcdef";
        String rawMessage = "Failed connecting to postgresql://dbuser:fakeSecretPass999@db.internal:5432/db with token=" + fakeJwt + " and key=" + fakeApiKey;

        List<StackTraceFrame> frames = List.of(
                StackTraceFrame.of("com.traceback.db.Connector", "connect", "Connector.java", 42)
        );

        ParsedException pe = new ParsedException("java.sql.SQLException", rawMessage, frames, null);

        // Assert that secrets are redacted
        assertThat(pe.message()).doesNotContain("fakeSecretPass999");
        assertThat(pe.message()).doesNotContain(fakeJwt);
        assertThat(pe.message()).doesNotContain(fakeApiKey);
        assertThat(pe.message()).contains("postgresql://dbuser:[REDACTED]@db.internal:5432/db");
        assertThat(pe.message()).contains("[REDACTED JWT]");
        assertThat(pe.message()).contains("token=[REDACTED JWT]");
        assertThat(pe.message()).contains("key=[REDACTED]");

        // Assert that structural properties and frames are preserved
        assertThat(pe.exceptionType()).isEqualTo("java.sql.SQLException");
        assertThat(pe.frames()).hasSize(1);
        assertThat(pe.frames().get(0).fileName()).isEqualTo("Connector.java");
        assertThat(pe.frames().get(0).lineNumber()).isEqualTo(42);
        assertThat(pe.frames().get(0).methodName()).isEqualTo("connect");
    }

    @Test
    @DisplayName("Should recursively sanitize nested cause exception messages in exception chain")
    void shouldSanitizeNestedCauseMessages() {
        String causeMsg = "Authentication failed: Bearer mockFakeBearerToken1234567890";
        ParsedException cause = new ParsedException("javax.security.auth.AuthException", causeMsg, Collections.emptyList(), null);

        String wrapperMsg = "Data access error: password=innerSecretPassword123";
        ParsedException wrapper = new ParsedException("org.springframework.dao.DataAccessException", wrapperMsg, Collections.emptyList(), cause);

        assertThat(wrapper.message()).doesNotContain("innerSecretPassword123");
        assertThat(wrapper.message()).contains("password=[REDACTED]");

        assertThat(wrapper.cause()).isNotNull();
        assertThat(wrapper.cause().message()).doesNotContain("mockFakeBearerToken1234567890");
        assertThat(wrapper.cause().message()).contains("Bearer [REDACTED]");
    }

    @Test
    @DisplayName("Should prevent secret leakage through JSON serialization of InvestigationResult with primaryException")
    void shouldPreventSecretLeakageThroughInvestigationResultSerialization() throws Exception {
        String sensitiveMsg = "OperationalError: connection to postgresql://admin:prodSuperSecretPass@localhost:5432/orders failed";
        ParsedException pe = new ParsedException("OperationalError", sensitiveMsg, Collections.emptyList(), null);

        UUID invId = UUID.randomUUID();
        InvestigationResult result = new InvestigationResult(
                invId,
                "order-service",
                "production",
                Instant.now(),
                Instant.now(),
                "COMPLETED",
                5,
                1,
                pe,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList(),
                true,
                InvestigationMode.RUNTIME_FAILURE
        );

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).doesNotContain("prodSuperSecretPass");
        assertThat(json).contains("postgresql://admin:[REDACTED]@localhost:5432/orders");
        assertThat(json).contains("\"exceptionType\":\"OperationalError\"");
    }
}
