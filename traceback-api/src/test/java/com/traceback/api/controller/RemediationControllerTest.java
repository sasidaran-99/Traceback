package com.traceback.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.ExecuteRemediationRequest;
import com.traceback.api.dto.ProposeRemediationRequest;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.domain.RemediationOperationEventEntity;
import com.traceback.core.remediation.RemediationAdminService;
import com.traceback.core.remediation.RemediationBreakGlassService;
import com.traceback.core.remediation.RemediationConcurrencyConflictException;
import com.traceback.core.remediation.RemediationExecutionCoordinator;
import com.traceback.core.remediation.RemediationJournalService;
import com.traceback.core.remediation.RemediationOperationEventType;
import com.traceback.core.remediation.RemediationOperationNotFoundException;
import com.traceback.core.remediation.RemediationOperationStatus;
import com.traceback.core.remediation.RemediationOperationType;
import com.traceback.core.remediation.RemediationRecoveryCoordinator;
import com.traceback.core.remediation.RemediationTargetRefFrozenException;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.api.security.TestJwtKeyProvider;
import com.traceback.api.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class RemediationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestJwtKeyProvider keyProvider;

    @MockBean
    private RemediationExecutionCoordinator executionCoordinator;

    @MockBean
    private RemediationRecoveryCoordinator recoveryCoordinator;

    @MockBean
    private RemediationAdminService adminService;

    @MockBean
    private RemediationBreakGlassService breakGlassService;

    @MockBean
    private RemediationJournalService journalService;

    @MockBean
    private RemediationOperationRepository operationRepository;

    @MockBean
    private InvestigationRepository investigationRepository;

    @Test
    @DisplayName("Request without Bearer token returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/remediations/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Mutation request by VIEWER role returns 403 Forbidden")
    void viewerRoleMutationReturns403() throws Exception {
        UUID opId = UUID.randomUUID();
        ExecuteRemediationRequest request = new ExecuteRemediationRequest("worker-1");
        String token = keyProvider.createToken("read-only-user", List.of("VIEWER"));

        mockMvc.perform(post("/api/remediations/" + opId + "/execute")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Ref freeze by OPERATOR role returns 403 Forbidden (ADMIN required)")
    void operatorFreezeReturns403() throws Exception {
        String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));

        mockMvc.perform(post("/api/remediations/refs/freeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("refName", "refs/heads/main"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Proposal with nonexistent investigationId returns 404 Not Found")
    void proposalWithNonexistentInvestigationReturns404() throws Exception {
        UUID invId = UUID.randomUUID();
        when(investigationRepository.findById(invId)).thenReturn(Optional.empty());
        String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));

        ProposeRemediationRequest request = new ProposeRemediationRequest(
                invId,
                "idem-inv-404",
                "/var/git/repo",
                "refs/heads/main",
                "0123456789abcdef0123456789abcdef01234567",
                "abcdef0123456789abcdef0123456789abcdef01",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                null
        );

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Investigation not found: " + invId));
    }

    @Test
    @DisplayName("Proposal with mismatched repositoryPath vs investigation returns 400 Bad Request")
    void proposalWithMismatchedRepositoryPathReturns400() throws Exception {
        UUID invId = UUID.randomUUID();
        InvestigationEntity inv = new InvestigationEntity();
        inv.setId(invId);
        inv.setRepositoryPath("C:/repos/other-repo");
        when(investigationRepository.findById(invId)).thenReturn(Optional.of(inv));
        String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));

        ProposeRemediationRequest request = new ProposeRemediationRequest(
                invId,
                "idem-repo-mismatch",
                "C:/repos/actual-repo",
                "refs/heads/main",
                "0123456789abcdef0123456789abcdef01234567",
                "abcdef0123456789abcdef0123456789abcdef01",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                null
        );

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/remediations/proposals derives actor from security context rather than JSON body")
    void proposeRemediationDerivesActorFromContext() throws Exception {
        UUID opId = UUID.randomUUID();
        RemediationOperationEntity entity = new RemediationOperationEntity();
        entity.setId(opId);
        entity.setIdempotencyKey("idem-api-1");
        entity.setRepositoryPath("/var/git/repo");
        entity.setTargetRef("refs/heads/main");
        entity.setExpectedOldSha("0123456789abcdef0123456789abcdef01234567");
        entity.setProposedNewSha("abcdef0123456789abcdef0123456789abcdef01");
        entity.setOperationType(RemediationOperationType.BRANCH_PATCH_PROMOTION);
        entity.setStatus(RemediationOperationStatus.CREATED);
        entity.setFencingToken(1L);
        entity.setInitiatedBy("authenticated-operator");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        ArgumentCaptor<String> actorCaptor = ArgumentCaptor.forClass(String.class);

        when(executionCoordinator.proposeOperation(
                any(), any(), any(), any(), any(), any(), any(), actorCaptor.capture()
        )).thenReturn(entity);

        String token = keyProvider.createToken("authenticated-operator", List.of("OPERATOR"));

        ProposeRemediationRequest request = new ProposeRemediationRequest(
                null,
                "idem-api-1",
                "/var/git/repo",
                "refs/heads/main",
                "0123456789abcdef0123456789abcdef01234567",
                "abcdef0123456789abcdef0123456789abcdef01",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                null
        );

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(opId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.targetRef").value("refs/heads/main"));

        assertEquals("authenticated-operator", actorCaptor.getValue());
    }

    @Test
    @DisplayName("POST /api/remediations/proposals with invalid SHA returns 400 Bad Request")
    void proposeRemediationInvalidSha() throws Exception {
        String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));
        ProposeRemediationRequest request = new ProposeRemediationRequest(
                null,
                "idem-api-invalid",
                "/var/git/repo",
                "refs/heads/main",
                "INVALID_SHA",
                "SHORT_SHA",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                null
        );

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("POST /api/remediations/proposals on frozen ref returns 409 Conflict")
    void proposeRemediationFrozenRef() throws Exception {
        when(executionCoordinator.proposeOperation(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RemediationTargetRefFrozenException("refs/heads/main", "Ref frozen for reconciliation"));
        String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));

        ProposeRemediationRequest request = new ProposeRemediationRequest(
                null,
                "idem-api-frozen",
                "/var/git/repo",
                "refs/heads/main",
                "0123456789abcdef0123456789abcdef01234567",
                "abcdef0123456789abcdef0123456789abcdef01",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                null
        );

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/remediations/{operationId}/execute executes operation and returns 200 OK")
    void executeRemediationSuccess() throws Exception {
        UUID opId = UUID.randomUUID();
        RemediationOperationEntity executed = new RemediationOperationEntity();
        executed.setId(opId);
        executed.setIdempotencyKey("idem-exec");
        executed.setRepositoryPath("/var/git/repo");
        executed.setTargetRef("refs/heads/main");
        executed.setExpectedOldSha("0123456789abcdef0123456789abcdef01234567");
        executed.setProposedNewSha("abcdef0123456789abcdef0123456789abcdef01");
        executed.setOperationType(RemediationOperationType.BRANCH_PATCH_PROMOTION);
        executed.setStatus(RemediationOperationStatus.PERSISTED);
        executed.setFencingToken(1L);
        executed.setInitiatedBy("api-user");
        executed.setCreatedAt(Instant.now());
        executed.setUpdatedAt(Instant.now());

        when(executionCoordinator.executeOperation(eq(opId), eq("worker-node-1"))).thenReturn(executed);

        ExecuteRemediationRequest request = new ExecuteRemediationRequest("worker-node-1");
        String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));

        mockMvc.perform(post("/api/remediations/" + opId + "/execute")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(opId.toString()))
                .andExpect(jsonPath("$.status").value("PERSISTED"));
    }

    @Test
    @DisplayName("GET /api/remediations/{operationId} returns operation status")
    void getOperationStatusSuccess() throws Exception {
        UUID opId = UUID.randomUUID();
        RemediationOperationEntity entity = new RemediationOperationEntity();
        entity.setId(opId);
        entity.setIdempotencyKey("idem-get");
        entity.setRepositoryPath("/var/git/repo");
        entity.setTargetRef("refs/heads/main");
        entity.setExpectedOldSha("0123456789abcdef0123456789abcdef01234567");
        entity.setProposedNewSha("abcdef0123456789abcdef0123456789abcdef01");
        entity.setOperationType(RemediationOperationType.BRANCH_PATCH_PROMOTION);
        entity.setStatus(RemediationOperationStatus.PERSISTED);
        entity.setFencingToken(1L);
        entity.setInitiatedBy("api-user");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        when(operationRepository.findById(opId)).thenReturn(Optional.of(entity));
        String token = keyProvider.createToken("viewer-user", List.of("VIEWER"));

        mockMvc.perform(get("/api/remediations/" + opId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(opId.toString()))
                .andExpect(jsonPath("$.status").value("PERSISTED"));
    }

    @Test
    @DisplayName("GET /api/remediations/{operationId}/events returns append-only audit trail")
    void getOperationEventsSuccess() throws Exception {
        UUID opId = UUID.randomUUID();
        RemediationOperationEventEntity event = new RemediationOperationEventEntity();
        event.setId(UUID.randomUUID());
        event.setOperationId(opId);
        event.setFencingToken(1L);
        event.setEventType(RemediationOperationEventType.CREATED);
        event.setToStatus(RemediationOperationStatus.CREATED);
        event.setRefName("refs/heads/main");
        event.setCreatedAt(Instant.now());

        when(journalService.getEventsForOperation(opId)).thenReturn(List.of(event));
        String token = keyProvider.createToken("viewer-user", List.of("VIEWER"));

        mockMvc.perform(get("/api/remediations/" + opId + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].operationId").value(opId.toString()))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"));
    }

    @Test
    @DisplayName("POST /api/remediations/refs/freeze succeeds, but unfreeze returns 410 Gone and never calls adminService")
    void freezeAndDecommissionedUnfreeze() throws Exception {
        doNothing().when(adminService).freezeTargetRef(eq("refs/heads/main"), any());
        String token = keyProvider.createToken("admin-user", List.of("ADMIN"));

        mockMvc.perform(post("/api/remediations/refs/freeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("refName", "refs/heads/main")
                        .param("reason", "Administrative freeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refName").value("refs/heads/main"))
                .andExpect(jsonPath("$.isFrozen").value(true));

        verify(adminService).freezeTargetRef(eq("refs/heads/main"), eq("Administrative freeze"));

        mockMvc.perform(post("/api/remediations/refs/unfreeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("refName", "refs/heads/main"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"))
                .andExpect(jsonPath("$.refName").value("refs/heads/main"));

        verify(adminService, never()).unfreezeTargetRef(any());
    }
}
