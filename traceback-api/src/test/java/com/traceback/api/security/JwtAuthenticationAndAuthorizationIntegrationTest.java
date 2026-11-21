package com.traceback.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.ExecuteRemediationRequest;
import com.traceback.api.dto.ProposeRemediationRequest;
import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.remediation.RemediationOperationStatus;
import com.traceback.core.remediation.RemediationOperationType;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService;
import com.traceback.core.remediation.RemediationAdminService;
import com.traceback.core.remediation.RemediationBreakGlassService;
import com.traceback.core.remediation.RemediationExecutionCoordinator;
import com.traceback.core.remediation.RemediationJournalService;
import com.traceback.core.remediation.RemediationRecoveryCoordinator;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class JwtAuthenticationAndAuthorizationIntegrationTest {

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

    @MockBean
    private ActiveInvestigationBootstrapService bootstrapService;

    private UUID sampleOpId;
    private RemediationOperationEntity sampleEntity;

    @BeforeEach
    void setUp() {
        sampleOpId = UUID.randomUUID();
        sampleEntity = new RemediationOperationEntity();
        sampleEntity.setId(sampleOpId);
        sampleEntity.setIdempotencyKey("key-1");
        sampleEntity.setRepositoryPath("C:/repo");
        sampleEntity.setTargetRef("refs/heads/main");
        sampleEntity.setExpectedOldSha("0123456789abcdef0123456789abcdef01234567");
        sampleEntity.setProposedNewSha("abcdef0123456789abcdef0123456789abcdef01");
        sampleEntity.setOperationType(RemediationOperationType.BRANCH_PATCH_PROMOTION);
        sampleEntity.setStatus(RemediationOperationStatus.CREATED);
        sampleEntity.setFencingToken(1L);
        sampleEntity.setInitiatedBy("alice");
        sampleEntity.setCreatedAt(java.time.Instant.now());
        sampleEntity.setUpdatedAt(java.time.Instant.now());
        when(operationRepository.findById(sampleOpId)).thenReturn(Optional.of(sampleEntity));
    }

    @Test
    @DisplayName("Request without Bearer token returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/remediations/" + sampleOpId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Forged X-Actor-Id and X-Actor-Role: ADMIN headers without Bearer token are rejected with 401")
    void forgedAdminHeadersWithoutTokenRejected() throws Exception {
        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header("X-Actor-Id", "attacker")
                        .header("X-Actor-Role", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Forged X-Actor-Role: ADMIN header alongside a VIEWER Bearer token does not escalate privilege (returns 403)")
    void forgedAdminHeaderWithViewerTokenCannotEscalate() throws Exception {
        String viewerToken = keyProvider.builder()
                .subject("viewer-user")
                .roles(List.of("VIEWER"))
                .build();

        ExecuteRemediationRequest request = new ExecuteRemediationRequest("worker-1");

        mockMvc.perform(post("/api/remediations/" + sampleOpId + "/execute")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .header("X-Actor-Role", "ADMIN") // Header spoofing attempt
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Valid VIEWER token attempting mutation operation returns 403 Forbidden")
    void viewerTokenAttemptingMutationReturns403() throws Exception {
        String viewerToken = keyProvider.builder()
                .subject("viewer-user")
                .roles(List.of("VIEWER"))
                .build();

        ExecuteRemediationRequest request = new ExecuteRemediationRequest("worker-1");

        mockMvc.perform(post("/api/remediations/" + sampleOpId + "/execute")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Valid VIEWER token attempting GET operation succeeds with 200 OK")
    void viewerTokenCanRead() throws Exception {
        String viewerToken = keyProvider.builder()
                .subject("viewer-user")
                .roles(List.of("VIEWER"))
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sampleOpId.toString()));
    }

    @Test
    @DisplayName("Valid OPERATOR token attempting administrative freeze returns 403 Forbidden")
    void operatorTokenAttemptingAdminFreezeReturns403() throws Exception {
        String operatorToken = keyProvider.builder()
                .subject("operator-user")
                .roles(List.of("OPERATOR"))
                .build();

        mockMvc.perform(post("/api/remediations/refs/freeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .param("refName", "refs/heads/main"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Valid ADMIN token accessing administrative freeze succeeds with 200 OK")
    void adminTokenAccessingFreezeSucceeds() throws Exception {
        String adminToken = keyProvider.builder()
                .subject("admin-user")
                .roles(List.of("ADMIN"))
                .build();

        doNothing().when(adminService).freezeTargetRef(eq("refs/heads/main"), any());

        mockMvc.perform(post("/api/remediations/refs/freeze")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("refName", "refs/heads/main")
                        .param("reason", "Security freeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refName").value("refs/heads/main"))
                .andExpect(jsonPath("$.isFrozen").value(true));
    }

    @Test
    @DisplayName("Client-supplied initiatedBy attempting identity spoofing returns 403 Forbidden")
    void clientSuppliedInitiatedBySpoofingRejected() throws Exception {
        String operatorToken = keyProvider.builder()
                .subject("alice-operator")
                .roles(List.of("OPERATOR"))
                .build();

        ProposeRemediationRequest request = new ProposeRemediationRequest(
                null,
                "key-spoof",
                "C:/repo",
                "refs/heads/feature",
                "0123456789abcdef0123456789abcdef01234567",
                "abcdef0123456789abcdef0123456789abcdef01",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "mallory" // Identity spoofing attempt: does not match JWT subject "alice-operator"
        );

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Identity spoofing detected: initiatedBy does not match authenticated subject"));
    }

    @Test
    @DisplayName("Client proposal with matching initiatedBy derives actor from authenticated JWT subject")
    void validProposalDerivesActorFromJwtSubject() throws Exception {
        String operatorToken = keyProvider.builder()
                .subject("alice-operator")
                .roles(List.of("OPERATOR"))
                .build();

        ProposeRemediationRequest request = new ProposeRemediationRequest(
                null,
                "key-valid",
                "C:/repo",
                "refs/heads/feature",
                "0123456789abcdef0123456789abcdef01234567",
                "abcdef0123456789abcdef0123456789abcdef01",
                RemediationOperationType.BRANCH_PATCH_PROMOTION,
                "alice-operator"
        );

        when(executionCoordinator.proposeOperation(any(), any(), any(), any(), any(), any(), any(), eq("alice-operator")))
                .thenReturn(sampleEntity);

        mockMvc.perform(post("/api/remediations/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("JWT with invalid cryptographic signature returns 401 Unauthorized")
    void invalidSignatureReturns401() throws Exception {
        String invalidSigToken = keyProvider.builder()
                .subject("attacker")
                .roles(List.of("ADMIN"))
                .signWithInvalidKey()
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + invalidSigToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("JWT with wrong issuer returns 401 Unauthorized")
    void wrongIssuerReturns401() throws Exception {
        String wrongIssuerToken = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .issuer("https://rogue-idp.com")
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongIssuerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("JWT with wrong audience returns 401 Unauthorized")
    void wrongAudienceReturns401() throws Exception {
        String wrongAudienceToken = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .audience("another-app")
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudienceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Expired JWT returns 401 Unauthorized")
    void expiredTokenReturns401() throws Exception {
        String expiredToken = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("JWT with unknown role claims returns 401 Unauthorized (fail-closed)")
    void unknownRoleClaimReturns401() throws Exception {
        String unknownRoleToken = keyProvider.builder()
                .subject("alice")
                .roles(List.of("SUPER_ADMIN"))
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unknownRoleToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Access to /api/admin/bootstrap-active-investigation without authentication returns 401")
    void adminBootstrapUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/admin/bootstrap-active-investigation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Access to /api/admin/bootstrap-active-investigation with OPERATOR token returns 403 Forbidden")
    void adminBootstrapWithOperatorReturns403() throws Exception {
        String operatorToken = keyProvider.builder()
                .subject("op")
                .roles(List.of("OPERATOR"))
                .build();

        mockMvc.perform(post("/api/admin/bootstrap-active-investigation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Access to investigation endpoints without authentication returns 401 Unauthorized")
    void investigationEndpointUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/investigations/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Access to event ingestion endpoint without authentication returns 401 Unauthorized")
    void eventIngestionUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Public health endpoints remain accessible without authentication")
    void publicHealthAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("CORS OPTIONS preflight request succeeds without authentication")
    void corsPreflightAllowedWithoutAuth() throws Exception {
        mockMvc.perform(options("/api/remediations/proposals")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unmapped endpoint is denied with 403 Forbidden even with valid ADMIN JWT (denyAll)")
    void unmappedEndpointDeniedEvenWithValidAdminJwt() throws Exception {
        String adminToken = keyProvider.builder()
                .subject("super-admin")
                .roles(List.of("ADMIN"))
                .build();

        mockMvc.perform(get("/api/unmapped-endpoint-sensitive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Unmapped endpoint without authentication returns 401 Unauthorized")
    void unmappedEndpointUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/unmapped-endpoint-sensitive"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token with alg: none is rejected with 401 Unauthorized")
    void algNoneTokenReturns401() throws Exception {
        String noneToken = keyProvider.createUnsignedToken("hacker", List.of("ADMIN"));

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + noneToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Symmetric HMAC token is rejected with 401 Unauthorized")
    void hmacTokenReturns401() throws Exception {
        byte[] secret = "secret-key-at-least-256-bits-long-32-bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hmacToken = keyProvider.createHmacToken("hacker", List.of("ADMIN"), secret);

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hmacToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token with missing exp is rejected with 401 Unauthorized")
    void missingExpReturns401() throws Exception {
        String token = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .omitExpiresAt()
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token with missing iat is rejected with 401 Unauthorized")
    void missingIatReturns401() throws Exception {
        String token = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .omitIssuedAt()
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token with missing nbf is rejected with 401 Unauthorized")
    void missingNbfReturns401() throws Exception {
        String token = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .omitNotBefore()
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token with future iat is rejected with 401 Unauthorized")
    void futureIatReturns401() throws Exception {
        String token = keyProvider.builder()
                .subject("alice")
                .roles(List.of("OPERATOR"))
                .issuedAt(Instant.now().plusSeconds(600))
                .build();

        mockMvc.perform(get("/api/remediations/" + sampleOpId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("VIEWER role attempting POST /api/events returns 403 Forbidden")
    void viewerRoleCannotPostEvent() throws Exception {
        String viewerToken = keyProvider.builder()
                .subject("viewer-user")
                .roles(List.of("VIEWER"))
                .build();

        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("VIEWER role attempting POST /api/investigations returns 403 Forbidden")
    void viewerRoleCannotPostInvestigation() throws Exception {
        String viewerToken = keyProvider.builder()
                .subject("viewer-user")
                .roles(List.of("VIEWER"))
                .build();

        mockMvc.perform(post("/api/investigations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("VIEWER role can read GET /api/investigations (returns 200 OK)")
    void viewerRoleCanGetInvestigations() throws Exception {
        String viewerToken = keyProvider.builder()
                .subject("viewer-user")
                .roles(List.of("VIEWER"))
                .build();

        mockMvc.perform(get("/api/investigations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }
}
