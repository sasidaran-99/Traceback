package com.traceback.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.AgentInvestigationRequest;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "OPERATOR")
class AgentInvestigationIdempotencyApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InvestigationAgent investigationAgent;

    @Test
    @DisplayName("POST /api/investigations/agent with existing investigationId returns existing report without rerunning agent")
    void shouldReturnExistingReportWithoutExecutingAgent() throws Exception {
        UUID invId = UUID.randomUUID();
        String reportId = "REP-existing-123";

        InvestigationAgentReport existingReport = new InvestigationAgentReport(
                reportId,
                invId,
                "Existing Authoritative Summary",
                "Primary root cause identified in commit ee51c22",
                Collections.emptyList(),
                List.of("EVD-GIT-ee51c22"),
                Collections.emptyList(),
                null,
                "Reasoning narrative",
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("Verification step"),
                Collections.emptyList(),
                AgentInvestigationStatus.PROBABLE,
                Collections.emptyList(),
                List.of(AgentInvestigationStep.of(1, "Deterministic Ingestion", "Ingested", Collections.emptyMap())),
                Instant.now()
        );

        when(investigationAgent.getReport(invId)).thenReturn(Optional.of(existingReport));

        AgentInvestigationRequest request = new AgentInvestigationRequest(
                invId, null, null, null, null, null, null, null, null, null, false
        );

        mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(reportId))
                .andExpect(jsonPath("$.investigationId").value(invId.toString()))
                .andExpect(jsonPath("$.finalStatus").value("PROBABLE"))
                .andExpect(jsonPath("$.summary").value("Existing Authoritative Summary"));

        // Crucial verification: agent.investigate(...) must NOT have been called!
        verify(investigationAgent, never()).investigate(any(UUID.class));
    }

    @Test
    @DisplayName("POST /api/investigations/agent with forceRerun=true explicitly triggers agent re-execution")
    void shouldTriggerAgentReexecutionWhenForceRerunIsTrue() throws Exception {
        UUID invId = UUID.randomUUID();
        String existingReportId = "REP-existing-123";
        String newReportId = "REP-rerun-456";

        InvestigationAgentReport existingReport = new InvestigationAgentReport(
                existingReportId, invId, "Existing Summary", "Old cause",
                Collections.emptyList(), List.of("EVD-1"), Collections.emptyList(),
                null, "Old reasoning", Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), AgentInvestigationStatus.PROBABLE,
                Collections.emptyList(), Collections.emptyList(), Instant.now()
        );
        InvestigationAgentReport freshReport = new InvestigationAgentReport(
                newReportId, invId, "Fresh Rerun Summary", "Fresh cause",
                Collections.emptyList(), List.of("EVD-2"), Collections.emptyList(),
                null, "Fresh reasoning", Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), AgentInvestigationStatus.PROBABLE,
                Collections.emptyList(), Collections.emptyList(), Instant.now()
        );

        when(investigationAgent.getReport(invId)).thenReturn(Optional.of(existingReport));
        when(investigationAgent.investigate(invId)).thenReturn(freshReport);

        AgentInvestigationRequest request = new AgentInvestigationRequest(
                invId, null, null, null, null, null, null, null, null, null, true
        );

        mockMvc.perform(post("/api/investigations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(newReportId))
                .andExpect(jsonPath("$.summary").value("Fresh Rerun Summary"));

        // Crucial verification: agent.investigate(invId) WAS called because forceRerun=true
        verify(investigationAgent).investigate(invId);
    }

    @Test
    @DisplayName("GET /api/investigations/{id}/agent-reports returns chronological report history")
    void shouldReturnChronologicalReportHistory() throws Exception {
        UUID invId = UUID.randomUUID();

        InvestigationAgentReport rep1 = new InvestigationAgentReport(
                "REP-001", invId, "Sum 1", "Cause 1", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null, "R1",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), AgentInvestigationStatus.PROBABLE, Collections.emptyList(),
                Collections.emptyList(), Instant.now().minusSeconds(100)
        );
        InvestigationAgentReport rep2 = new InvestigationAgentReport(
                "REP-002", invId, "Sum 2", "Cause 2", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null, "R2",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), AgentInvestigationStatus.CONFIRMED, Collections.emptyList(),
                Collections.emptyList(), Instant.now()
        );

        when(investigationAgent.getReportHistory(invId)).thenReturn(List.of(rep2, rep1));

        mockMvc.perform(get("/api/investigations/{id}/agent-reports", invId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportId").value("REP-002"))
                .andExpect(jsonPath("$[1].reportId").value("REP-001"));
    }
}
