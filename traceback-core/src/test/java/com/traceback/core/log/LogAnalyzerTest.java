package com.traceback.core.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogAnalyzerTest {

    private final LogAnalyzer analyzer = new LogAnalyzer();

    @Test
    @DisplayName("Should parse multiline application logs and detect first failure timestamp")
    void shouldParseMultilineLogs() {
        String logContent = """
                2026-10-10 14:15:00.100 INFO 1234 --- [main] c.t.p.PaymentApplication : Starting PaymentApplication v1.0
                2026-10-10 14:15:02.500 INFO 1234 --- [main] c.t.p.PaymentApplication : Started PaymentApplication in 2.4s
                2026-10-10 14:16:30.123 ERROR 1234 --- [http-nio-exec-1] c.t.p.s.PaymentProcessingService : Connection pool failure
                java.sql.SQLTimeoutException: Connection timeout after 3000ms
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                2026-10-10 14:16:35.456 ERROR 1234 --- [http-nio-exec-2] c.t.p.s.PaymentProcessingService : Connection pool failure
                java.sql.SQLTimeoutException: Connection timeout after 3000ms
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                2026-10-10 14:17:00.000 WARN 1234 --- [sched-1] c.t.p.s.HealthCheckService : Database response slow
                """;

        LogEvidence evidence = analyzer.analyzeContent(logContent);

        assertThat(evidence.hasFailures()).isTrue();
        assertThat(evidence.totalErrors()).isEqualTo(2);
        assertThat(evidence.totalWarnings()).isEqualTo(1);
        assertThat(evidence.firstObservedFailure()).isEqualTo(Instant.parse("2026-10-10T14:16:30.123Z"));
        assertThat(evidence.lastObservedFailure()).isEqualTo(Instant.parse("2026-10-10T14:16:35.456Z"));

        assertThat(evidence.primaryErrorSignature()).isNotNull();
        assertThat(evidence.primaryErrorSignature().exceptionType()).isEqualTo("java.sql.SQLTimeoutException");
        assertThat(evidence.primaryErrorSignature().messagePattern()).contains("Connection timeout after 3000ms");

        assertThat(evidence.groupedErrors()).hasSize(1);
        assertThat(evidence.groupedErrors().values().iterator().next()).hasSize(2);
    }

    @Test
    @DisplayName("Should parse structured JSON log lines")
    void shouldParseStructuredJsonLogs() {
        String jsonLogs = """
                {"timestamp":"2026-10-10T14:16:30.000Z","level":"INFO","logger":"c.t.Payment","message":"Handling checkout"}
                {"timestamp":"2026-10-10T14:16:35.500Z","level":"ERROR","logger":"c.t.Payment","message":"Database timeout","stack_trace":"java.sql.SQLTimeoutException: Timeout\\n\\tat com.example.Db.query(Db.java:12)"}
                """;

        LogEvidence evidence = analyzer.analyzeContent(jsonLogs);

        assertThat(evidence.hasFailures()).isTrue();
        assertThat(evidence.totalErrors()).isEqualTo(1);
        assertThat(evidence.firstObservedFailure()).isEqualTo(Instant.parse("2026-10-10T14:16:35.500Z"));
        assertThat(evidence.primaryException()).isNotNull();
        assertThat(evidence.primaryException().exceptionType()).isEqualTo("java.sql.SQLTimeoutException");
    }
}
