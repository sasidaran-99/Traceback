package com.traceback.core.stacktrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalStackTraceParserTest {

    private final StackTraceParser parser = new StackTraceParser();

    @Test
    @DisplayName("Should parse standard Java stack trace with nested cause")
    void shouldParseJavaStackTraceWithCause() {
        String javaTrace = """
                org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection
                \tat org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator.doTranslate(SQLErrorCodeSQLExceptionTranslator.java:277)
                \tat com.traceback.service.OrderService.createOrder(OrderService.java:45)
                Caused by: java.sql.SQLTimeoutException: Connection timeout after 3000ms
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:186)
                \tat org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:215)
                """;

        Optional<ParsedException> parsed = parser.parse(javaTrace);

        assertThat(parsed).isPresent();
        ParsedException top = parsed.get();
        assertThat(top.exceptionType()).isEqualTo("org.springframework.dao.DataAccessResourceFailureException");
        assertThat(top.message()).isEqualTo("Unable to acquire JDBC Connection");
        assertThat(top.frames()).hasSize(2);
        assertThat(top.frames().get(1).fileName()).isEqualTo("OrderService.java");
        assertThat(top.frames().get(1).lineNumber()).isEqualTo(45);

        ParsedException root = top.getRootCause();
        assertThat(root.exceptionType()).isEqualTo("java.sql.SQLTimeoutException");
        assertThat(root.message()).isEqualTo("Connection timeout after 3000ms");
        assertThat(root.frames()).hasSize(2);
    }

    @Test
    @DisplayName("Should parse standard Python traceback")
    void shouldParsePythonTraceback() {
        String pythonTrace = """
                Traceback (most recent call last):
                  File "/app/server.py", line 85, in handle_request
                    response = process_order(order_id)
                  File "/app/orders/processor.py", line 24, in process_order
                    db.save(order)
                  File "/app/db/connection.py", line 112, in save
                    raise OperationalError("Connection to postgresql://localhost:5432 failed: timeout")
                OperationalError: Connection to postgresql://localhost:5432 failed: timeout
                """;

        Optional<ParsedException> parsed = parser.parse(pythonTrace);

        assertThat(parsed).isPresent();
        ParsedException pe = parsed.get();
        assertThat(pe.exceptionType()).isEqualTo("OperationalError");
        assertThat(pe.message()).isEqualTo("Connection to postgresql://localhost:5432 failed: timeout");
        assertThat(pe.frames()).hasSize(3);

        StackTraceFrame topFrame = pe.frames().get(0);
        assertThat(topFrame.fileName()).isEqualTo("server.py");
        assertThat(topFrame.lineNumber()).isEqualTo(85);
        assertThat(topFrame.methodName()).isEqualTo("handle_request");

        StackTraceFrame bottomFrame = pe.frames().get(2);
        assertThat(bottomFrame.fileName()).isEqualTo("connection.py");
        assertThat(bottomFrame.lineNumber()).isEqualTo(112);
        assertThat(bottomFrame.methodName()).isEqualTo("save");
    }

    @Test
    @DisplayName("Should parse Python chained exceptions and correctly identify root cause")
    void shouldParsePythonChainedExceptions() {
        String chainedTrace = """
                Traceback (most recent call last):
                  File "/app/db/socket.py", line 30, in connect
                    sock.connect(("127.0.0.1", 5432))
                ConnectionRefusedError: [Errno 111] Connection refused

                During handling of the above exception, another exception occurred:

                Traceback (most recent call last):
                  File "/app/main.py", line 15, in run
                    service.connect()
                DatabaseError: Failed to initialize connection pool
                """;

        Optional<ParsedException> parsed = parser.parse(chainedTrace);

        assertThat(parsed).isPresent();
        ParsedException top = parsed.get();
        assertThat(top.exceptionType()).isEqualTo("DatabaseError");
        assertThat(top.message()).isEqualTo("Failed to initialize connection pool");
        assertThat(top.frames().get(0).fileName()).isEqualTo("main.py");

        ParsedException root = top.getRootCause();
        assertThat(root.exceptionType()).isEqualTo("ConnectionRefusedError");
        assertThat(root.message()).isEqualTo("[Errno 111] Connection refused");
        assertThat(root.frames().get(0).fileName()).isEqualTo("socket.py");
    }

    @Test
    @DisplayName("Should parse standard Node.js / TypeScript stack trace")
    void shouldParseNodeStackTrace() {
        String nodeTrace = """
                TypeError: Cannot read properties of undefined (reading 'query')
                    at Database.query (/app/src/db/client.js:42:15)
                    at handleRequest (/app/src/controllers/orderController.ts:18:20)
                    at Layer.handle [as handle_request] (/app/node_modules/express/lib/router/layer.js:95:5)
                """;

        Optional<ParsedException> parsed = parser.parse(nodeTrace);

        assertThat(parsed).isPresent();
        ParsedException pe = parsed.get();
        assertThat(pe.exceptionType()).isEqualTo("TypeError");
        assertThat(pe.message()).isEqualTo("Cannot read properties of undefined (reading 'query')");
        assertThat(pe.frames()).hasSize(3);

        StackTraceFrame frame0 = pe.frames().get(0);
        assertThat(frame0.declaringClass()).isEqualTo("Database");
        assertThat(frame0.methodName()).isEqualTo("query");
        assertThat(frame0.fileName()).isEqualTo("client.js");
        assertThat(frame0.lineNumber()).isEqualTo(42);

        StackTraceFrame frame1 = pe.frames().get(1);
        assertThat(frame1.fileName()).isEqualTo("orderController.ts");
        assertThat(frame1.lineNumber()).isEqualTo(18);
    }

    @Test
    @DisplayName("Should parse Node.js stack trace with cause block")
    void shouldParseNodeStackTraceWithCause() {
        String nodeWithCause = """
                Error: Failed to fetch user profile
                    at fetchProfile (/app/src/services/userService.js:25:9)
                    at handleLogin (/app/src/auth.js:14:5)
                [cause]: Error: connect ECONNREFUSED 127.0.0.1:5432
                    at TCPConnectWrap.afterConnect (/app/src/net/tcp.js:100:12)
                """;

        Optional<ParsedException> parsed = parser.parse(nodeWithCause);

        assertThat(parsed).isPresent();
        ParsedException top = parsed.get();
        assertThat(top.exceptionType()).isEqualTo("Error");
        assertThat(top.message()).isEqualTo("Failed to fetch user profile");
        assertThat(top.frames().get(0).fileName()).isEqualTo("userService.js");

        ParsedException root = top.getRootCause();
        assertThat(root.message()).contains("ECONNREFUSED");
        assertThat(root.frames().get(0).fileName()).isEqualTo("tcp.js");
    }
}
