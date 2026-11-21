package com.traceback.api.migration;

import com.traceback.api.integration.PostgreSqlAvailableCondition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("postgres-test")
@ExtendWith(PostgreSqlAvailableCondition.class)
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @org.junit.jupiter.api.BeforeEach
    void verifyDatabaseIsolation() {
        String dbUrl = environment.getProperty("spring.datasource.url", "");
        if (dbUrl.toLowerCase().contains("traceback_db")) {
            throw new IllegalStateException(
                "CRITICAL SAFETY VIOLATION: Integration tests must NEVER connect to production/active database 'traceback_db'! Current URL=" + dbUrl
            );
        }
        assertThat(dbUrl).contains("traceback_test_db");
    }

    @Test
    @DisplayName("Flyway migrations should execute and apply V1, V2, V3, V4, and V5 schema successfully")
    void flywayMigrationShouldApplySuccessfully() throws Exception {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");

        List<String> tableNames = new ArrayList<>();
        List<String> agentReportColumns = new ArrayList<>();
        List<String> evidenceColumns = new ArrayList<>();
        List<String> claimColumns = new ArrayList<>();
        List<String> hypothesisColumns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }
            try (ResultSet rs = metaData.getColumns(null, null, "investigation_agent_reports", null)) {
                while (rs.next()) {
                    agentReportColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            try (ResultSet rs = metaData.getColumns(null, null, "investigation_evidence", null)) {
                while (rs.next()) {
                    evidenceColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            try (ResultSet rs = metaData.getColumns(null, null, "investigation_claims", null)) {
                while (rs.next()) {
                    claimColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            try (ResultSet rs = metaData.getColumns(null, null, "investigation_hypotheses", null)) {
                while (rs.next()) {
                    hypothesisColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
        }

        assertThat(tableNames).contains(
                "events", "incidents", "event_relationships", "hypotheses",
                "investigations", "investigation_evidence", "investigation_claims",
                "investigation_agent_reports", "investigation_agent_steps", "investigation_timeline_events",
                "investigation_hypotheses"
        );

        assertThat(agentReportColumns).contains(
                "primary_suspected_root_cause",
                "supporting_evidence_ids",
                "contradictory_evidence_ids"
        );

        assertThat(evidenceColumns).contains("id", "evidence_id", "investigation_id");
        assertThat(claimColumns).contains("id", "claim_id", "investigation_id");
        assertThat(hypothesisColumns).contains(
                "id", "hypothesis_id", "investigation_id", "rank", "confidence_score",
                "status", "summary", "suspected_commit_sha", "confidence_breakdown"
        );
    }
}
