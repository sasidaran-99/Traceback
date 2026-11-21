package com.traceback.api.integration;

import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService.BootstrapReport;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PostgreSqlAvailableCondition.class)
@SpringBootTest
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgreSqlActiveInvestigationBootstrapTest {

    private static final Path BACKUP_PATH = resolveBackupPath();

    private static Path resolveBackupPath() {
        Path p1 = Path.of("fixtures", "complete_active_investigation_backup.json");
        Path p2 = Path.of("..", "fixtures", "complete_active_investigation_backup.json");
        Path p3 = Path.of("C:\\Users\\Sasidaran\\.gemini\\antigravity\\brain\\312124e7-6010-40db-ad58-48917858073c\\scratch\\complete_active_investigation_backup.json");
        if (Files.exists(p1)) return p1;
        if (Files.exists(p2)) return p2;
        return p3;
    }

    @Autowired
    private ActiveInvestigationBootstrapService bootstrapService;

    @Autowired
    private InvestigationRepository investigationRepository;

    @Autowired
    private InvestigationEvidenceRepository evidenceRepository;

    @Autowired
    private InvestigationClaimRepository claimRepository;

    @Autowired
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired
    private InvestigationAgentStepRepository agentStepRepository;

    @Autowired
    private InvestigationTimelineRepository timelineRepository;

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
    @Order(1)
    @DisplayName("Should bootstrap active TacitIQ EconomicTimes investigation into live PostgreSQL 16 database")
    void shouldBootstrapActiveInvestigationIntoPostgreSql() {
        assertThat(BACKUP_PATH).exists();

        BootstrapReport report = bootstrapService.importFromBackup(BACKUP_PATH, true);

        assertThat(report.success()).isTrue();
        assertThat(report.investigationId()).isEqualTo(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(report.reportId()).isEqualTo(ActiveInvestigationBootstrapService.TARGET_REPORT_ID);
        assertThat(report.evidenceCount()).isEqualTo(103);
        assertThat(report.claimCount()).isEqualTo(17);
        assertThat(report.timelineCount()).isEqualTo(35);
        assertThat(report.stepCount()).isEqualTo(5);
        assertThat(report.hashVerifiedCount()).isEqualTo(103);
        assertThat(report.hashMismatchCount()).isEqualTo(0);

        // Verify entities in PostgreSQL 16
        Optional<InvestigationEntity> invOpt = investigationRepository.findById(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(invOpt).isPresent();
        InvestigationEntity inv = invOpt.get();
        assertThat(inv.getMode()).isEqualTo(InvestigationMode.WORKING_TREE_FORENSICS);
        assertThat(inv.getRepositoryPath()).contains("EconomicTimes");
        assertThat(inv.getStatus()).isEqualTo("COMPLETED");

        List<EvidenceItemEntity> evidenceList = evidenceRepository.findByInvestigationId(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(evidenceList).hasSize(103);

        List<ClaimEntity> claimsList = claimRepository.findByInvestigationId(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(claimsList).hasSize(17);

        Optional<AgentReportEntity> reportOpt = agentReportRepository.findLatestByInvestigationId(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(reportOpt).isPresent();
        assertThat(reportOpt.get().getReportId()).isEqualTo(ActiveInvestigationBootstrapService.TARGET_REPORT_ID);

        List<AgentStepEntity> steps = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(steps).hasSize(5);

        List<TimelineEventEntity> timeline = timelineRepository.findByInvestigationIdOrderByTimestampAsc(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(timeline).hasSize(35);
    }

    @Test
    @Order(2)
    @DisplayName("Should detect existing investigation and skip duplicate bootstrap without force flag")
    void shouldSkipDuplicateBootstrap() {
        BootstrapReport duplicateReport = bootstrapService.importFromBackup(BACKUP_PATH, false);
        assertThat(duplicateReport.success()).isTrue();
        assertThat(duplicateReport.message()).containsIgnoringCase("already exists");
    }

    @Test
    @Order(3)
    @DisplayName("Should maintain exact counts without duplicating agent steps on repeated forced bootstrap")
    void shouldBeIdempotentOnForcedRepeatedBootstrap() {
        BootstrapReport repeatedReport = bootstrapService.importFromBackup(BACKUP_PATH, true);
        assertThat(repeatedReport.success()).isTrue();
        assertThat(repeatedReport.stepCount()).isEqualTo(5);

        List<AgentStepEntity> steps = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(steps).hasSize(5);

        List<EvidenceItemEntity> evidenceList = evidenceRepository.findByInvestigationId(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(evidenceList).hasSize(103);

        List<ClaimEntity> claimsList = claimRepository.findByInvestigationId(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        assertThat(claimsList).hasSize(17);
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanupTestDatabase(@Autowired InvestigationRepository invRepo) {
        if (invRepo != null && invRepo.existsById(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID)) {
            invRepo.deleteById(ActiveInvestigationBootstrapService.TARGET_INVESTIGATION_ID);
        }
    }
}
