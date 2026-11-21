package com.traceback.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService.BootstrapReport;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveInvestigationBootstrapIdempotencyTest {

    private InvestigationRepository investigationRepository;
    private InvestigationEvidenceRepository evidenceRepository;
    private InvestigationClaimRepository claimRepository;
    private InvestigationAgentReportRepository agentReportRepository;
    private InvestigationAgentStepRepository agentStepRepository;
    private InvestigationTimelineRepository timelineRepository;
    private ConcreteEvidenceRegistry evidenceRegistry;
    private ActiveInvestigationBootstrapService bootstrapService;

    @TempDir
    Path tempDir;

    private Path backupFile;
    private static final UUID TARGET_INV_ID = UUID.fromString("5c48eb24-a00e-435a-aff8-88879ea113fb");
    private static final UUID UNRELATED_INV_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeEach
    void setUp() throws IOException {
        investigationRepository = mock(InvestigationRepository.class);
        evidenceRepository = mock(InvestigationEvidenceRepository.class);
        claimRepository = mock(InvestigationClaimRepository.class);
        agentReportRepository = mock(InvestigationAgentReportRepository.class);
        agentStepRepository = mock(InvestigationAgentStepRepository.class);
        timelineRepository = mock(InvestigationTimelineRepository.class);
        evidenceRegistry = new ConcreteEvidenceRegistry();

        bootstrapService = new ActiveInvestigationBootstrapService(
                investigationRepository,
                evidenceRepository,
                claimRepository,
                agentReportRepository,
                agentStepRepository,
                timelineRepository,
                evidenceRegistry,
                null,
                null,
                new ObjectMapper()
        );

        // Create a minimal synthetic backup json with 5 steps
        String backupJson = """
        {
          "investigationId": "5c48eb24-a00e-435a-aff8-88879ea113fb",
          "investigation": {
            "serviceName": "TacitIQ-EconomicTimes",
            "environment": "Local Development",
            "investigationMode": "WORKING_TREE_FORENSICS",
            "status": "COMPLETED"
          },
          "agentSteps": [
            { "stepNumber": 1, "stepName": "Deterministic Ingestion", "description": "Step 1 desc", "timestamp": "2026-11-08T18:22:48.456Z" },
            { "stepNumber": 2, "stepName": "External Technical Research", "description": "Step 2 desc", "timestamp": "2026-11-08T18:22:48.458Z" },
            { "stepNumber": 3, "stepName": "Bifrost AI Gateway Reasoning", "description": "Step 3 desc", "timestamp": "2026-11-08T18:22:48.461Z" },
            { "stepNumber": 4, "stepName": "Evidence Grounding & Claim Validation", "description": "Step 4 desc", "timestamp": "2026-11-08T18:22:48.464Z" },
            { "stepNumber": 5, "stepName": "Final Verdict", "description": "Step 5 desc", "timestamp": "2026-11-08T18:22:48.468Z" }
          ]
        }
        """;
        backupFile = tempDir.resolve("test_backup.json");
        Files.writeString(backupFile, backupJson);
    }

    @Test
    @DisplayName("First import persists exactly 5 agent steps with deterministic UUIDs")
    void firstImport_persistsExactCounts() {
        when(investigationRepository.existsById(TARGET_INV_ID)).thenReturn(false);
        when(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(TARGET_INV_ID))
                .thenReturn(Collections.emptyList());

        BootstrapReport report = bootstrapService.importFromBackup(backupFile, false);

        assertThat(report.success()).isTrue();
        assertThat(report.stepCount()).isEqualTo(5);

        ArgumentCaptor<AgentStepEntity> captor = ArgumentCaptor.forClass(AgentStepEntity.class);
        verify(agentStepRepository, times(5)).save(captor.capture());

        List<AgentStepEntity> savedSteps = captor.getAllValues();
        assertThat(savedSteps).hasSize(5);
        for (int i = 0; i < 5; i++) {
            AgentStepEntity s = savedSteps.get(i);
            assertThat(s.getStepNumber()).isEqualTo(i + 1);
            assertThat(s.getInvestigationId()).isEqualTo(TARGET_INV_ID);
            // Deterministic UUID verification
            UUID expectedUuid = UUID.nameUUIDFromBytes((TARGET_INV_ID + "-step-" + (i + 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(s.getId()).isEqualTo(expectedUuid);
        }
    }

    @Test
    @DisplayName("Second import without force skips duplicate import idempotently")
    void secondImportWithoutForce_skipsDuplicate() {
        when(investigationRepository.existsById(TARGET_INV_ID)).thenReturn(true);
        when(agentStepRepository.countByInvestigationId(TARGET_INV_ID)).thenReturn(5L);

        BootstrapReport report = bootstrapService.importFromBackup(backupFile, false);

        assertThat(report.success()).isTrue();
        assertThat(report.message()).containsIgnoringCase("already exists");
        verify(agentStepRepository, never()).save(any());
        verify(agentStepRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("Forced repeated import cleans existing investigation steps and replaces with exactly 5 steps")
    void forcedRepeatedImport_replacesStepsWithoutDuplication() {
        // Mock that 5 steps already exist
        List<AgentStepEntity> existing = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            existing.add(new AgentStepEntity(UUID.randomUUID(), TARGET_INV_ID, i, "Old Step " + i, "COMPLETED", "Old", "{}", 0L));
        }
        when(investigationRepository.existsById(TARGET_INV_ID)).thenReturn(true);
        when(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(TARGET_INV_ID)).thenReturn(existing);

        BootstrapReport report = bootstrapService.importFromBackup(backupFile, true);

        assertThat(report.success()).isTrue();
        assertThat(report.stepCount()).isEqualTo(5);

        // Verify that existing steps for this investigation were purged
        verify(agentStepRepository).deleteAll(existing);
        verify(agentStepRepository).flush();

        // Verify that exactly 5 new steps were saved
        verify(agentStepRepository, times(5)).save(any(AgentStepEntity.class));
    }

    @Test
    @DisplayName("Idempotent bootstrap strictly scopes step operations and preserves unrelated investigations")
    void preservesUnrelatedInvestigations() {
        // Steps belonging to an unrelated investigation
        AgentStepEntity unrelatedStep = new AgentStepEntity(
                UUID.randomUUID(), UNRELATED_INV_ID, 1, "Unrelated Step", "COMPLETED", "Unrelated", "{}", 0L
        );

        when(investigationRepository.existsById(TARGET_INV_ID)).thenReturn(true);
        // Only return target investigation steps when queried by target investigation ID
        when(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(TARGET_INV_ID))
                .thenReturn(Collections.emptyList());
        when(agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(UNRELATED_INV_ID))
                .thenReturn(List.of(unrelatedStep));

        BootstrapReport report = bootstrapService.importFromBackup(backupFile, true);

        assertThat(report.success()).isTrue();

        // Verify deleteAll was never invoked on unrelated steps
        verify(agentStepRepository, never()).deleteAll(List.of(unrelatedStep));
        // Verify find was strictly scoped to TARGET_INV_ID
        verify(agentStepRepository).findByInvestigationIdOrderByStepNumberAsc(TARGET_INV_ID);
        verify(agentStepRepository, never()).findByInvestigationIdOrderByStepNumberAsc(UNRELATED_INV_ID);
    }
}
