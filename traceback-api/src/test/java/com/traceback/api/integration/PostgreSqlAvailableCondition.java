package com.traceback.api.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * JUnit 5 condition that verifies whether PostgreSQL is reachable on localhost:5432
 * before running integration tests against the live database.
 */
public class PostgreSqlAvailableCondition implements ExecutionCondition {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final int TIMEOUT_MS = 1500;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            return ConditionEvaluationResult.enabled("PostgreSQL is reachable on " + HOST + ":" + PORT);
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled(
                    "PostgreSQL is not reachable on " + HOST + ":" + PORT + " (" + e.getMessage() + "). Skipping PostgreSQL integration tests.");
        }
    }
}
