package com.traceback.api.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

/**
 * Base test infrastructure for PostgreSQL 16 integration testing.
 * Uses Testcontainers PostgreSQL 16 if Docker is available.
 * If Docker is unavailable, local PostgreSQL fallback is disabled by default and
 * strictly requires explicit opt-in via:
 *   -Dtraceback.test.allow-local-postgres-fallback=true
 *
 * Safety invariants:
 * - Local fallback is disabled by default; fails fast if Docker is absent and opt-in is false/absent.
 * - Strict database allowlist: only 'traceback_test_db' is permitted in fallback mode.
 * - Explicitly rejects 'traceback_db' and any other application database.
 * - Enforces DatabaseMetaData.getDatabaseMajorVersion() >= 16 before creating test schema.
 * - Enforces randomized schema isolation (test_schema_<uuid>) and restricts teardown
 *   strictly to the generated test schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PostgreSqlTestcontainersBase {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlTestcontainersBase.class);
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    public static final String PROPERTY_ALLOW_LOCAL_FALLBACK = "traceback.test.allow-local-postgres-fallback";
    public static final String PERMITTED_FALLBACK_DATABASE = "traceback_test_db";
    private static final Set<String> REJECTED_APPLICATION_DATABASES = Set.of(
            "traceback_db",
            "traceback",
            "traceback_prod",
            "traceback_production",
            "traceback_dev",
            "traceback_development"
    );

    protected PostgreSQLContainer<?> container;
    protected HikariDataSource dataSource;
    protected String testSchema;
    protected boolean usingContainer = false;

    @BeforeAll
    public void setUpPostgreSqlInfrastructure() throws Exception {
        testSchema = "test_schema_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        if (isDockerAvailable()) {
            try {
                log.info("Docker daemon detected. Initializing Testcontainers PostgreSQL 16 ({})", POSTGRES_IMAGE);
                container = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                        .withDatabaseName("testdb")
                        .withUsername("testuser")
                        .withPassword("testpass");
                container.start();
                usingContainer = true;
                initDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
                log.info("Testcontainers PostgreSQL 16 container started successfully at {}", container.getJdbcUrl());
            } catch (Exception e) {
                log.warn("Failed to initialize Testcontainers container despite Docker check: {}. Evaluating fallback.", e.getMessage());
                fallbackToLocalPostgres();
            }
        } else {
            log.info("Docker daemon not available. Evaluating local PostgreSQL fallback...");
            fallbackToLocalPostgres();
        }

        initializeSchemaAndTables();
    }

    private void fallbackToLocalPostgres() throws Exception {
        boolean allowFallback = Boolean.parseBoolean(System.getProperty(PROPERTY_ALLOW_LOCAL_FALLBACK));
        if (!allowFallback) {
            throw new IllegalStateException(
                    "Docker daemon is unavailable and local PostgreSQL fallback is disabled by default. " +
                    "To explicitly opt-in to running integration tests against a dedicated local test database, run with: " +
                    "-D" + PROPERTY_ALLOW_LOCAL_FALLBACK + "=true"
            );
        }

        String fallbackUrl = System.getProperty("traceback.test.local-postgres-url", "jdbc:postgresql://localhost:5432/" + PERMITTED_FALLBACK_DATABASE);
        validateFallbackDatabaseUrl(fallbackUrl);

        if (!isLocalPostgreSqlListening(fallbackUrl)) {
            throw new IllegalStateException("Local PostgreSQL fallback is enabled, but no PostgreSQL server is listening at: " + fallbackUrl);
        }

        String username = System.getProperty("traceback.test.local-postgres-user", "traceback_user");
        String password = System.getProperty("traceback.test.local-postgres-password", "traceback_password");

        initDataSource(fallbackUrl, username, password);
    }

    private void validateFallbackDatabaseUrl(String fallbackUrl) {
        if (fallbackUrl == null || !fallbackUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("Invalid fallback JDBC URL: " + fallbackUrl);
        }

        // Extract database name from URL (strip jdbc: prefix, extract path component)
        String cleanUrl = fallbackUrl.substring(5); // strip "jdbc:"
        URI uri;
        try {
            uri = URI.create(cleanUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed fallback JDBC URL: " + fallbackUrl, e);
        }

        String path = uri.getPath();
        String dbName = (path != null && path.startsWith("/")) ? path.substring(1) : path;

        if (dbName == null || dbName.isBlank()) {
            throw new IllegalStateException("No database name found in fallback JDBC URL: " + fallbackUrl);
        }

        // Remove query parameters if present
        int queryIndex = dbName.indexOf('?');
        if (queryIndex != -1) {
            dbName = dbName.substring(0, queryIndex);
        }

        // Explicitly reject traceback_db and any application/production databases
        if (REJECTED_APPLICATION_DATABASES.contains(dbName.toLowerCase())) {
            throw new IllegalStateException(
                    "CRITICAL SECURITY VIOLATION: Target fallback database '" + dbName +
                    "' is an application database. Integration tests are strictly prohibited from connecting to application databases!"
            );
        }

        // Strict allowlist: only traceback_test_db is permitted
        if (!PERMITTED_FALLBACK_DATABASE.equalsIgnoreCase(dbName)) {
            throw new IllegalStateException(
                    "CRITICAL SECURITY VIOLATION: Database '" + dbName +
                    "' is not permitted for local test fallback! The only permitted test database is '" +
                    PERMITTED_FALLBACK_DATABASE + "'."
            );
        }
    }

    private void initDataSource(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(15000);
        config.setAutoCommit(false);
        this.dataSource = new HikariDataSource(config);
    }

    private void initializeSchemaAndTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Verify PostgreSQL server major version >= 16 before creating test schema
            DatabaseMetaData metaData = conn.getMetaData();
            int majorVersion = metaData.getDatabaseMajorVersion();
            if (majorVersion < 16) {
                throw new IllegalStateException(
                        "PostgreSQL server version must be >= 16. Detected version: " + majorVersion +
                        " (" + metaData.getDatabaseProductVersion() + ")"
                );
            }

            // In fallback mode, double check catalog name
            if (!usingContainer) {
                String catalog = conn.getCatalog();
                if (catalog != null && !PERMITTED_FALLBACK_DATABASE.equalsIgnoreCase(catalog)) {
                    throw new IllegalStateException(
                            "CRITICAL SECURITY VIOLATION: Connected database catalog is '" + catalog +
                            "', which violates the strict allowlist of '" + PERMITTED_FALLBACK_DATABASE + "'!"
                    );
                }
            }

            try (Statement stmt = conn.createStatement()) {
                // Set search path or create isolated schema
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + testSchema);
                stmt.execute("SET search_path TO " + testSchema + ", public");

                // Create V6 tables inside isolated test schema
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS remediation_target_ref_locks (
                        ref_name VARCHAR(255) PRIMARY KEY,
                        is_frozen BOOLEAN NOT NULL DEFAULT FALSE,
                        frozen_reason TEXT,
                        frozen_at TIMESTAMP WITH TIME ZONE,
                        current_fencing_token BIGINT NOT NULL DEFAULT 1,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS remediation_operations (
                        id UUID PRIMARY KEY,
                        idempotency_key VARCHAR(128) NOT NULL,
                        repository_path TEXT NOT NULL,
                        target_ref VARCHAR(255) NOT NULL,
                        expected_old_sha VARCHAR(64) NOT NULL,
                        proposed_new_sha VARCHAR(64) NOT NULL,
                        operation_type VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        worker_id VARCHAR(128),
                        fencing_token BIGINT NOT NULL DEFAULT 1,
                        lease_expires_at TIMESTAMP WITH TIME ZONE,
                        initiated_by VARCHAR(128) NOT NULL,
                        failure_classification VARCHAR(64),
                        failure_details TEXT,
                        recovery_status VARCHAR(64),
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uq_remediation_op_idempotency UNIQUE (idempotency_key)
                    );
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS remediation_operation_events (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        operation_id UUID NOT NULL REFERENCES remediation_operations(id) ON DELETE CASCADE,
                        fencing_token BIGINT NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        from_status VARCHAR(32),
                        to_status VARCHAR(32),
                        ref_name VARCHAR(255),
                        expected_old_sha VARCHAR(64),
                        result_sha VARCHAR(64),
                        jgit_result VARCHAR(64),
                        details TEXT,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                """);

                conn.commit();
                log.info("Initialized test schema {} on PostgreSQL {} with remediation tables",
                        testSchema, majorVersion);
            }
        }
    }

    @AfterAll
    public void tearDownPostgreSqlInfrastructure() {
        if (dataSource != null && !dataSource.isClosed()) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                // Strict guard: only drop the randomized schema created by this test execution
                if (testSchema != null && testSchema.startsWith("test_schema_") && !testSchema.contains(";") && !testSchema.contains(" ")) {
                    stmt.execute("DROP SCHEMA IF EXISTS " + testSchema + " CASCADE");
                    conn.commit();
                    log.info("Dropped test schema {}", testSchema);
                } else {
                    log.warn("Skipping teardown drop schema: invalid or unexpected testSchema '{}'", testSchema);
                }
            } catch (Exception e) {
                log.warn("Failed to clean up test schema {}: {}", testSchema, e.getMessage());
            }
            dataSource.close();
        }

        if (container != null && container.isRunning()) {
            container.stop();
            log.info("Stopped Testcontainers PostgreSQL container");
        }
    }

    protected Connection getIsolatedConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + testSchema + ", public");
        }
        return conn;
    }

    private boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isLocalPostgreSqlListening(String jdbcUrl) {
        try {
            URI uri = URI.create(jdbcUrl.substring(5));
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            int port = uri.getPort() != -1 ? uri.getPort() : 5432;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
