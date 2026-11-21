package com.traceback.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.CancelBreakGlassRequest;
import com.traceback.api.dto.FinalizeBreakGlassRequest;
import com.traceback.api.dto.ProposeBreakGlassRequest;
import com.traceback.api.dto.RejectBreakGlassRequest;
import com.traceback.api.security.TestJwtKeyProvider;
import com.traceback.api.security.TestSecurityConfig;
import com.traceback.core.domain.RemediationBreakGlassRequestEntity;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService;
import com.traceback.core.remediation.BreakGlassAdminIdentity;
import com.traceback.core.remediation.BreakGlassFinalizationOutcome;
import com.traceback.core.remediation.RemediationAdminService;
import com.traceback.core.remediation.RemediationBreakGlassDecision;
import com.traceback.core.remediation.RemediationBreakGlassService;
import com.traceback.core.remediation.RemediationBreakGlassStatus;
import com.traceback.core.remediation.RemediationConcurrencyConflictException;
import com.traceback.core.remediation.RemediationExecutionCoordinator;
import com.traceback.core.remediation.RemediationJournalService;
import com.traceback.core.remediation.RemediationOperationNotFoundException;
import com.traceback.core.remediation.RemediationOperationStatus;
import com.traceback.core.remediation.RemediationPreconditionConflictException;
import com.traceback.core.remediation.RemediationRecoveryCoordinator;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import com.traceback.core.security.RemediationAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class RemediationBreakGlassControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestJwtKeyProvider keyProvider;

    @MockBean
    private RemediationBreakGlassService breakGlassService;

    @MockBean
    private RemediationExecutionCoordinator executionCoordinator;

    @MockBean
    private RemediationRecoveryCoordinator recoveryCoordinator;

    @MockBean
    private RemediationAdminService adminService;

    @MockBean
    private RemediationJournalService journalService;

    @MockBean
    private RemediationOperationRepository operationRepository;

    @MockBean
    private InvestigationRepository investigationRepository;

    @MockBean
    private ActiveInvestigationBootstrapService bootstrapService;

    private UUID operationId;
    private UUID requestId;
    private String adminToken;
    private String operatorToken;
    private String viewerToken;

    private static final String VALID_HEX_64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        operationId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        adminToken = keyProvider.createToken("admin-user-1", List.of("ADMIN"));
        operatorToken = keyProvider.createToken("operator-user-1", List.of("OPERATOR"));
        viewerToken = keyProvider.createToken("viewer-user-1", List.of("VIEWER"));
    }

    private RemediationBreakGlassRequestEntity buildSampleEntity(UUID id, UUID opId, RemediationBreakGlassStatus status) {
        RemediationBreakGlassRequestEntity entity = new RemediationBreakGlassRequestEntity();
        entity.setId(id);
        entity.setOperationId(opId);
        entity.setRepositoryPath("C:/secrets/repo/path"); // internal path that must be redacted
        entity.setTargetRef("refs/heads/main");
        entity.setExpectedOldSha("1111111111111111111111111111111111111111");
        entity.setProposedNewSha("2222222222222222222222222222222222222222");
        entity.setSnapshotGitSha("1111111111111111111111111111111111111111");
        entity.setOperationFencingToken(1L);
        entity.setRequestedDecision(RemediationBreakGlassDecision.KEEP_FROZEN);
        entity.setStatus(status);
        entity.setRationale("Administrative reconciliation justification");
        entity.setInitiatorIssuer(TestJwtKeyProvider.DEFAULT_ISSUER);
        entity.setInitiatorSubject("admin-user-1");
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plusSeconds(1800));
        return entity;
    }

    @Nested
    @DisplayName("Role-Based Authorization & Endpoint Access")
    class AuthorizationTests {

        @Test
        @DisplayName("Unauthenticated request to any break-glass endpoint returns 401")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/remediations/" + operationId + "/break-glass"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ADMIN role can invoke all 4 mutation endpoints")
        void adminCanInvokeMutations() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            when(breakGlassService.proposeRequest(any(), any())).thenReturn(sample);
            when(breakGlassService.finalizeKeepFrozen(any(), any())).thenReturn(BreakGlassFinalizationOutcome.executedKeptFrozen(sample));
            when(breakGlassService.rejectRequest(any(), any())).thenReturn(sample);
            when(breakGlassService.cancelRequest(any(), any())).thenReturn(sample);

            // 1. Propose
            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isCreated());

            // 2. Finalize
            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, "Finalize notes");
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isOk());

            // 3. Reject
            RejectBreakGlassRequest rejReq = new RejectBreakGlassRequest("Valid rejection reason");
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/reject")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rejReq)))
                    .andExpect(status().isOk());

            // 4. Cancel
            CancelBreakGlassRequest canReq = new CancelBreakGlassRequest("Valid cancellation reason");
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(canReq)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("OPERATOR role cannot invoke any break-glass mutation endpoint (403 Forbidden)")
        void operatorCannotInvokeMutations() throws Exception {
            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isForbidden());

            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isForbidden());

            RejectBreakGlassRequest rejReq = new RejectBreakGlassRequest("Rejection reason");
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/reject")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rejReq)))
                    .andExpect(status().isForbidden());

            CancelBreakGlassRequest canReq = new CancelBreakGlassRequest("Cancellation reason");
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(canReq)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("VIEWER role cannot invoke any break-glass mutation endpoint (403 Forbidden)")
        void viewerCannotInvokeMutations() throws Exception {
            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN, OPERATOR, and VIEWER roles all have read access (200 OK)")
        void readEndpointsPermittedForAllRoles() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            when(breakGlassService.getRequest(operationId, requestId)).thenReturn(Optional.of(sample));
            when(breakGlassService.getRequestsForOperation(operationId)).thenReturn(List.of(sample));

            // Test GET /{requestId} for all 3 roles
            for (String token : List.of(adminToken, operatorToken, viewerToken)) {
                mockMvc.perform(get("/api/remediations/" + operationId + "/break-glass/" + requestId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(requestId.toString()))
                        .andExpect(jsonPath("$.status").value("PENDING_SECOND_APPROVAL"));
            }

            // Test GET / for all 3 roles
            for (String token : List.of(adminToken, operatorToken, viewerToken)) {
                mockMvc.perform(get("/api/remediations/" + operationId + "/break-glass")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].id").value(requestId.toString()));
            }
        }
    }

    @Nested
    @DisplayName("JWT Identity Derivation & Spoofing Defense")
    class IdentityAndSpoofingTests {

        @Test
        @DisplayName("Missing 'iss' claim in JWT returns 401 Unauthorized")
        void missingIssuerReturns401() throws Exception {
            String tokenWithoutIssuer = keyProvider.builder()
                    .issuer(null)
                    .subject("admin-user-1")
                    .roles(List.of("ADMIN"))
                    .build();

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutIssuer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("Missing 'sub' claim in JWT returns 401 Unauthorized")
        void missingSubjectReturns401() throws Exception {
            String tokenWithoutSubject = keyProvider.builder()
                    .omitSubject()
                    .roles(List.of("ADMIN"))
                    .build();

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutSubject)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("Forged actor headers (X-Actor-Id, X-Actor-Role, X-Tenant-Id) cannot bypass role check or alter identity")
        void forgedHeadersCannotElevatePrivilegesOrAlterIdentity() throws Exception {
            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);

            // Attempt to escalate privileges from OPERATOR to ADMIN using forged headers
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                            .header("X-Actor-Id", "admin-spoofed")
                            .header("X-Actor-Role", "ADMIN")
                            .header("X-Tenant-Id", "tenant-spoofed")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isForbidden());

            // Verify that for an ADMIN, the extracted identity strictly matches JWT claims, ignoring headers
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            ArgumentCaptor<BreakGlassAdminIdentity> identityCaptor = ArgumentCaptor.forClass(BreakGlassAdminIdentity.class);
            when(breakGlassService.proposeRequest(any(), identityCaptor.capture())).thenReturn(sample);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .header("X-Actor-Id", "spoofed-user")
                            .header("X-Actor-Role", "SUPER_ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isCreated());

            BreakGlassAdminIdentity captured = identityCaptor.getValue();
            assertEquals("admin-user-1", captured.subject());
            assertEquals(TestJwtKeyProvider.DEFAULT_ISSUER, captured.issuer());
        }
    }

    @Nested
    @DisplayName("Dual-Custody Business Invariants & Security Guardrails")
    class InvariantTests {

        @Test
        @DisplayName("Anti-self-approval rejection returns 403 Forbidden")
        void antiSelfApprovalReturns403() throws Exception {
            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, "Approval notes");
            when(breakGlassService.finalizeKeepFrozen(any(), any()))
                    .thenThrow(new RemediationAuthorizationException("Anti-self-approval violation: Approver cannot match proposal initiator"));

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value("Anti-self-approval violation: Approver cannot match proposal initiator"));
        }

        @Test
        @DisplayName("Cancellation by non-initiator returns 403 Forbidden")
        void nonInitiatorCancelReturns403() throws Exception {
            CancelBreakGlassRequest canReq = new CancelBreakGlassRequest("Cancellation reason");
            when(breakGlassService.cancelRequest(any(), any()))
                    .thenThrow(new RemediationAuthorizationException("Only the proposal initiator can cancel a pending break-glass request"));

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(canReq)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Rejection of decisions other than KEEP_FROZEN returns 400 Bad Request")
        void decisionsOtherThanKeepFrozenRejected() throws Exception {
            // Attempt to pass arbitrary or non-KEEP_FROZEN decision string
            String jsonPayload = "{\"decision\": \"CONFIRM_APPLIED\", \"executionNotes\": \"Test\"}";

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonPayload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("Evidence Triad Validation")
    class EvidenceTriadTests {

        @Test
        @DisplayName("All-null evidence triad is valid (201 Created)")
        void allNullEvidenceValid() throws Exception {
            ProposeBreakGlassRequest req = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            when(breakGlassService.proposeRequest(any(), any())).thenReturn(sample);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("All-present evidence triad is valid (201 Created)")
        void allPresentEvidenceValid() throws Exception {
            ProposeBreakGlassRequest req = new ProposeBreakGlassRequest(
                    "Valid justification rationale",
                    VALID_HEX_64,
                    "s3://audit-bucket/bundle-001.tar.gz",
                    "v1.2.0"
            );
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            sample.setEvidenceDigest(VALID_HEX_64);
            sample.setEvidenceBundleRef("s3://audit-bucket/bundle-001.tar.gz");
            sample.setEvidenceBundleVersion("v1.2.0");
            when(breakGlassService.proposeRequest(any(), any())).thenReturn(sample);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.evidenceDigest").value(VALID_HEX_64))
                    .andExpect(jsonPath("$.evidenceBundleRef").value("s3://audit-bucket/bundle-001.tar.gz"))
                    .andExpect(jsonPath("$.evidenceBundleVersion").value("v1.2.0"));
        }

        @Test
        @DisplayName("Partial evidence combinations are rejected with 400 Bad Request")
        void partialEvidenceCombinationsRejected() throws Exception {
            List<ProposeBreakGlassRequest> invalidRequests = List.of(
                    // Digest only
                    new ProposeBreakGlassRequest("Valid rationale text", VALID_HEX_64, null, null),
                    // Ref only
                    new ProposeBreakGlassRequest("Valid rationale text", null, "bundle-ref", null),
                    // Version only
                    new ProposeBreakGlassRequest("Valid rationale text", null, null, "v1.0"),
                    // Digest + Ref (missing Version)
                    new ProposeBreakGlassRequest("Valid rationale text", VALID_HEX_64, "bundle-ref", null),
                    // Digest + Version (missing Ref)
                    new ProposeBreakGlassRequest("Valid rationale text", VALID_HEX_64, null, "v1.0"),
                    // Ref + Version (missing Digest)
                    new ProposeBreakGlassRequest("Valid rationale text", null, "bundle-ref", "v1.0")
            );

            for (ProposeBreakGlassRequest req : invalidRequests) {
                mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.status").value(400));
            }
        }

        @Test
        @DisplayName("Uppercase hex digest is normalized to lowercase")
        void uppercaseDigestNormalized() throws Exception {
            String upperHex = VALID_HEX_64.toUpperCase();
            ProposeBreakGlassRequest req = new ProposeBreakGlassRequest(
                    "Valid justification rationale",
                    upperHex,
                    "bundle-ref",
                    "v1.0"
            );

            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            sample.setEvidenceDigest(VALID_HEX_64.toLowerCase());
            when(breakGlassService.proposeRequest(any(), any())).thenReturn(sample);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.evidenceDigest").value(VALID_HEX_64.toLowerCase()));
        }
    }

    @Nested
    @DisplayName("Error Handling, Conflict Mappings & Decommissioning")
    class ErrorAndDecommissioningTests {

        @Test
        @DisplayName("Invalid UUID path parameter returns 400 Bad Request")
        void invalidUuidReturns400() throws Exception {
            mockMvc.perform(get("/api/remediations/not-a-valid-uuid/break-glass")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            mockMvc.perform(get("/api/remediations/" + operationId + "/break-glass/not-a-uuid")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Missing operation returns 404 Not Found")
        void missingOperationReturns404() throws Exception {
            when(breakGlassService.proposeRequest(any(), any()))
                    .thenThrow(new RemediationOperationNotFoundException(operationId));

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("Missing request returns 404 Not Found")
        void missingRequestReturns404() throws Exception {
            when(breakGlassService.getRequest(operationId, requestId)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/remediations/" + operationId + "/break-glass/" + requestId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("Active break-glass request conflict returns 409 Conflict")
        void activeRequestConflictReturns409() throws Exception {
            when(breakGlassService.proposeRequest(any(), any()))
                    .thenThrow(new RemediationConcurrencyConflictException("Active break-glass request already exists"));

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Operation not in RECOVERY_REQUIRED returns 409 Conflict via RemediationPreconditionConflictException")
        void operationNotInRecoveryRequiredReturns409() throws Exception {
            when(breakGlassService.proposeRequest(any(), any()))
                    .thenThrow(new RemediationPreconditionConflictException("Cannot propose break-glass review: operation " + operationId + " is in status COMPLETED (expected RECOVERY_REQUIRED)"));

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Target ref not frozen returns 409 Conflict via RemediationPreconditionConflictException")
        void targetRefNotFrozenReturns409() throws Exception {
            when(breakGlassService.proposeRequest(any(), any()))
                    .thenThrow(new RemediationPreconditionConflictException("Cannot propose break-glass review: target ref 'refs/heads/main' is not frozen"));

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Unexpected IllegalStateException on break-glass endpoint returns 500 and is not masked as 409")
        void unexpectedIllegalStateExceptionReturns500() throws Exception {
            when(breakGlassService.proposeRequest(any(), any()))
                    .thenThrow(new IllegalStateException("Target ref lock record not found for: refs/heads/main"));

            ProposeBreakGlassRequest propReq = new ProposeBreakGlassRequest("Valid justification rationale", null, null, null);
            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(propReq)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.error").value("Internal Server Error"))
                    .andExpect(jsonPath("$.message").value("An unexpected internal error occurred"));
        }

        @Test
        @DisplayName("POST /api/remediations/refs/unfreeze returns 410 Gone and never calls adminService")
        void legacyUnfreezeReturns410Gone() throws Exception {
            mockMvc.perform(post("/api/remediations/refs/unfreeze")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .param("refName", "refs/heads/main"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.status").value(410))
                    .andExpect(jsonPath("$.error").value("Gone"))
                    .andExpect(jsonPath("$.refName").value("refs/heads/main"));

            verify(adminService, never()).unfreezeTargetRef(any());
        }
    }

    @Nested
    @DisplayName("Response Redaction & Finalization Semantics")
    class ResponseSemanticsTests {

        @Test
        @DisplayName("RemediationBreakGlassResponse strictly redacts absolute filesystem paths")
        void responseRedactsFilesystemPaths() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
            when(breakGlassService.getRequest(operationId, requestId)).thenReturn(Optional.of(sample));

            mockMvc.perform(get("/api/remediations/" + operationId + "/break-glass/" + requestId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(requestId.toString()))
                    .andExpect(jsonPath("$.repositoryPath").doesNotExist()) // absolute path redacted!
                    .andExpect(jsonPath("$.targetRef").value("refs/heads/main"));
        }

        @Test
        @DisplayName("Finalization outcome reports gitRecoveryExecuted=false, decisionApplied=true on success")
        void finalizationReportsGitRecoveryFalseOnSuccess() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN);
            BreakGlassFinalizationOutcome outcome = BreakGlassFinalizationOutcome.executedKeptFrozen(
                    sample, RemediationOperationStatus.RECOVERY_REQUIRED, true
            );
            when(breakGlassService.finalizeKeepFrozen(any(), any())).thenReturn(outcome);

            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, "Retained freeze");

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionApplied").value(true))
                    .andExpect(jsonPath("$.gitRecoveryExecuted").value(false)) // Never executed!
                    .andExpect(jsonPath("$.refFrozen").value(true))
                    .andExpect(jsonPath("$.operationStatus").value("RECOVERY_REQUIRED"))
                    .andExpect(jsonPath("$.status").value("EXECUTED_KEPT_FROZEN"))
                    .andExpect(jsonPath("$.request.repositoryPath").doesNotExist());
        }

        @Test
        @DisplayName("Finalization outcome on EXPIRED reports decisionApplied=false, gitRecoveryExecuted=false")
        void finalizationReportsExpiredOutcome() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.EXPIRED);
            BreakGlassFinalizationOutcome outcome = BreakGlassFinalizationOutcome.expired(
                    sample, RemediationOperationStatus.RECOVERY_REQUIRED, true
            );
            when(breakGlassService.finalizeKeepFrozen(any(), any())).thenReturn(outcome);

            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, null);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionApplied").value(false))
                    .andExpect(jsonPath("$.gitRecoveryExecuted").value(false))
                    .andExpect(jsonPath("$.refFrozen").value(true))
                    .andExpect(jsonPath("$.operationStatus").value("RECOVERY_REQUIRED"))
                    .andExpect(jsonPath("$.status").value("EXPIRED"));
        }

        @Test
        @DisplayName("Finalization outcome on STALE_INVALIDATED reflects actual operation status and ref frozen state")
        void finalizationReportsStaleInvalidatedOutcome() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.STALE_INVALIDATED);
            // Operation moved to PERSISTED and ref was unfrozen
            BreakGlassFinalizationOutcome outcome = BreakGlassFinalizationOutcome.staleInvalidated(
                    sample, "Operation is no longer in RECOVERY_REQUIRED status (found: PERSISTED)",
                    RemediationOperationStatus.PERSISTED, false
            );
            when(breakGlassService.finalizeKeepFrozen(any(), any())).thenReturn(outcome);

            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, null);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionApplied").value(false))
                    .andExpect(jsonPath("$.gitRecoveryExecuted").value(false))
                    .andExpect(jsonPath("$.refFrozen").value(false))
                    .andExpect(jsonPath("$.operationStatus").value("PERSISTED"))
                    .andExpect(jsonPath("$.status").value("STALE_INVALIDATED"));
        }

        @Test
        @DisplayName("Finalization outcome reports operationStatus as UNKNOWN when null without fabricating value")
        void finalizationReportsUnknownOperationStatusWhenNull() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.STALE_INVALIDATED);
            BreakGlassFinalizationOutcome outcome = new BreakGlassFinalizationOutcome(
                    RemediationBreakGlassStatus.STALE_INVALIDATED,
                    sample,
                    "Unknown state drift",
                    false,
                    null,
                    null
            );
            when(breakGlassService.finalizeKeepFrozen(any(), any())).thenReturn(outcome);

            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, null);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operationStatus").value("UNKNOWN"))
                    .andExpect(jsonPath("$.refFrozen").value(nullValue()));
        }

        @Test
        @DisplayName("Finalization outcome with null frozen state serializes refFrozen as null/unknown and not false")
        void finalizationReportsNullFrozenStateAsNullNotFalse() throws Exception {
            RemediationBreakGlassRequestEntity sample = buildSampleEntity(requestId, operationId, RemediationBreakGlassStatus.STALE_INVALIDATED);
            BreakGlassFinalizationOutcome outcome = new BreakGlassFinalizationOutcome(
                    RemediationBreakGlassStatus.STALE_INVALIDATED,
                    sample,
                    "Target ref record missing or unverified",
                    false,
                    RemediationOperationStatus.RECOVERY_REQUIRED,
                    null // unverified / unknown ref frozen state
            );
            when(breakGlassService.finalizeKeepFrozen(any(), any())).thenReturn(outcome);

            FinalizeBreakGlassRequest finReq = new FinalizeBreakGlassRequest(RemediationBreakGlassDecision.KEEP_FROZEN, null);

            mockMvc.perform(post("/api/remediations/" + operationId + "/break-glass/" + requestId + "/finalize")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(finReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionApplied").value(false))
                    .andExpect(jsonPath("$.gitRecoveryExecuted").value(false))
                    .andExpect(jsonPath("$.operationStatus").value("RECOVERY_REQUIRED"))
                    .andExpect(jsonPath("$.refFrozen").value(nullValue()))
                    .andExpect(jsonPath("$.status").value("STALE_INVALIDATED"));
        }
    }
}
