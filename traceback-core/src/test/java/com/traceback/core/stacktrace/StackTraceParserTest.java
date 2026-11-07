package com.traceback.core.stacktrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StackTraceParserTest {

    private final StackTraceParser parser = new StackTraceParser();

    @Test
    @DisplayName("Should parse standard single-cause Java stack trace")
    void shouldParseSingleCauseStackTrace() {
        String raw = """
                com.traceback.payment.exception.PaymentCalculationException: Invalid fee calculation: fee cannot be negative
                    at com.traceback.payment.service.PaymentFeeCalculator.calculateTransactionFee(PaymentFeeCalculator.java:58)
                    at com.traceback.payment.service.PaymentProcessingService.processPayment(PaymentProcessingService.java:112)
                    at com.traceback.payment.controller.PaymentController.checkout(PaymentController.java:45)
                    at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                """;

        Optional<ParsedException> result = parser.parse(raw);

        assertThat(result).isPresent();
        ParsedException pe = result.get();
        assertThat(pe.exceptionType()).isEqualTo("com.traceback.payment.exception.PaymentCalculationException");
        assertThat(pe.message()).isEqualTo("Invalid fee calculation: fee cannot be negative");
        assertThat(pe.frames()).hasSize(4);

        StackTraceFrame topFrame = pe.frames().get(0);
        assertThat(topFrame.declaringClass()).isEqualTo("com.traceback.payment.service.PaymentFeeCalculator");
        assertThat(topFrame.methodName()).isEqualTo("calculateTransactionFee");
        assertThat(topFrame.fileName()).isEqualTo("PaymentFeeCalculator.java");
        assertThat(topFrame.lineNumber()).isEqualTo(58);
        assertThat(topFrame.isApplicationFrame()).isTrue();

        StackTraceFrame jdkFrame = pe.frames().get(3);
        assertThat(jdkFrame.isApplicationFrame()).isFalse();
    }

    @Test
    @DisplayName("Should parse nested causes and identify the innermost root cause")
    void shouldParseNestedCauses() {
        String raw = """
                org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager for transaction
                    at org.springframework.orm.jpa.JpaTransactionManager.doBegin(JpaTransactionManager.java:448)
                Caused by: org.hibernate.exception.JDBCConnectionException: Unable to acquire JDBC Connection
                    at org.hibernate.exception.internal.SQLStateConverter.convert(SQLStateConverter.java:99)
                Caused by: java.sql.SQLTimeoutException: Connection timeout after 3000ms
                    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
                """;

        Optional<ParsedException> result = parser.parse(raw);

        assertThat(result).isPresent();
        ParsedException outermost = result.get();
        assertThat(outermost.exceptionType()).isEqualTo("org.springframework.transaction.CannotCreateTransactionException");
        assertThat(outermost.getCauseChain()).hasSize(3);

        ParsedException root = outermost.getRootCause();
        assertThat(root.exceptionType()).isEqualTo("java.sql.SQLTimeoutException");
        assertThat(root.message()).isEqualTo("Connection timeout after 3000ms");
        assertThat(root.frames()).hasSize(2);
        assertThat(root.frames().get(0).methodName()).isEqualTo("createTimeoutException");
    }

    @Test
    @DisplayName("Should handle empty or blank stack trace safely")
    void shouldHandleEmptyStackTraceSafely() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }
}
