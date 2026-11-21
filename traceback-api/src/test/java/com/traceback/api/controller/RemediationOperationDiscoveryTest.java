package com.traceback.api.controller;

import com.traceback.api.security.TestJwtKeyProvider;
import com.traceback.api.security.TestSecurityConfig;
import com.traceback.core.domain.RemediationOperationEntity;
import com.traceback.core.remediation.RemediationAdminService;
import com.traceback.core.remediation.RemediationExecutionCoordinator;
import com.traceback.core.remediation.RemediationJournalService;
import com.traceback.core.remediation.RemediationOperationStatus;
import com.traceback.core.remediation.RemediationOperationType;
import com.traceback.core.remediation.RemediationRecoveryCoordinator;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.RemediationOperationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class RemediationOperationDiscoveryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestJwtKeyProvider keyProvider;

    @MockBean
    private RemediationOperationRepository operationRepository;

    @MockBean
    private InvestigationRepository investigationRepository;

    @MockBean
    private RemediationExecutionCoordinator executionCoordinator;

    @MockBean
    private RemediationRecoveryCoordinator recoveryCoordinator;

    @MockBean
    private RemediationAdminService adminService;

    @MockBean
    private RemediationJournalService journalService;

    @MockBean
    private com.traceback.core.remediation.RemediationBreakGlassService breakGlassService;

    @MockBean
    private com.traceback.core.persistence.ActiveInvestigationBootstrapService bootstrapService;

    private RemediationOperationEntity buildOperation(
            UUID id,
            UUID investigationId,
            String idempotencyKey,
            RemediationOperationStatus status,
            Instant createdAt
    ) {
        RemediationOperationEntity entity = new RemediationOperationEntity();
        entity.setId(id);
        entity.setInvestigationId(investigationId);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRepositoryPath("C:/secure/enterprise/internal/repo.git");
        entity.setTargetRef("refs/heads/main");
        entity.setExpectedOldSha("0123456789abcdef0123456789abcdef01234567");
        entity.setProposedNewSha("abcdef0123456789abcdef0123456789abcdef01");
        entity.setOperationType(RemediationOperationType.BRANCH_PATCH_PROMOTION);
        entity.setStatus(status);
        entity.setFencingToken(1L);
        entity.setInitiatedBy("operator-1");
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);
        return entity;
    }

    @Nested
    @DisplayName("Role Authorization and Discovery Access")
    class AuthorizationTests {

        @Test
        @DisplayName("GET /api/remediations without token returns 401 Unauthorized")
        void unauthenticatedReturnsUnauthorized() throws Exception {
            UUID invId = UUID.randomUUID();

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("Unauthorized"));

            verifyNoInteractions(operationRepository);
        }

        @Test
        @DisplayName("GET /api/remediations with ROLE_ADMIN returns 200 OK")
        void adminCanDiscover() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("admin-user", List.of("ADMIN"));
            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(operationRepository).findByInvestigationIdOrderByCreatedAtDesc(invId);
        }

        @Test
        @DisplayName("GET /api/remediations with ROLE_OPERATOR returns 200 OK")
        void operatorCanDiscover() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));
            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(operationRepository).findByInvestigationIdOrderByCreatedAtDesc(invId);
        }

        @Test
        @DisplayName("GET /api/remediations with ROLE_VIEWER returns 200 OK")
        void viewerCanDiscover() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("viewer-user", List.of("VIEWER"));
            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(operationRepository).findByInvestigationIdOrderByCreatedAtDesc(invId);
        }
    }

    @Nested
    @DisplayName("Investigation-Scoped Filtering, Ordering, and Path Redaction")
    class FilteringAndRedactionTests {

        @Test
        @DisplayName("GET /api/remediations returns ordered list with repositoryPath strictly redacted to null")
        void returnsOrderedListWithRedactedPaths() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("viewer-user", List.of("VIEWER"));

            Instant now = Instant.now();
            UUID opId1 = UUID.randomUUID();
            UUID opId2 = UUID.randomUUID();

            RemediationOperationEntity op1 = buildOperation(
                    opId1, invId, "idem-newer", RemediationOperationStatus.CREATED, now
            );
            RemediationOperationEntity op2 = buildOperation(
                    opId2, invId, "idem-older", RemediationOperationStatus.PERSISTED, now.minusSeconds(300)
            );

            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of(op1, op2));

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(opId1.toString()))
                    .andExpect(jsonPath("$[0].status").value("CREATED"))
                    .andExpect(jsonPath("$[0].repositoryPath").value(nullValue()))
                    .andExpect(jsonPath("$[1].id").value(opId2.toString()))
                    .andExpect(jsonPath("$[1].status").value("PERSISTED"))
                    .andExpect(jsonPath("$[1].repositoryPath").value(nullValue()));

            verify(operationRepository).findByInvestigationIdOrderByCreatedAtDesc(invId);
        }

        @Test
        @DisplayName("GET /api/remediations with missing investigationId returns 400 Bad Request and does not query all")
        void missingInvestigationIdReturnsBadRequest() throws Exception {
            String token = keyProvider.createToken("admin-user", List.of("ADMIN"));

            mockMvc.perform(get("/api/remediations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value("investigationId parameter is mandatory"));

            verifyNoInteractions(operationRepository);
        }

        @Test
        @DisplayName("GET /api/remediations with malformed investigationId returns 400 Bad Request")
        void malformedInvestigationIdReturnsBadRequest() throws Exception {
            String token = keyProvider.createToken("admin-user", List.of("ADMIN"));

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", "not-a-valid-uuid")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"));

            verifyNoInteractions(operationRepository);
        }
    }

    @Nested
    @DisplayName("Failure Details Path Sanitization and Immutability (P1.2 Hardening)")
    class FailureDetailsSanitizationTests {

        @Test
        @DisplayName("Windows paths are removed from failureDetails in API response while preserving non-path details")
        void windowsPathsRemovedFromFailureDetails() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("viewer-user", List.of("VIEWER"));

            String rawDetails = "Git repository I/O error: Cannot lock C:\\Users\\Sasidaran\\repo\\.git\\refs\\heads\\main";
            RemediationOperationEntity op = buildOperation(
                    UUID.randomUUID(), invId, "idem-fail-win", RemediationOperationStatus.FAILED, Instant.now()
            );
            op.setFailureDetails(rawDetails);

            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of(op));

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].failureDetails").value("Git repository I/O error: Cannot lock <redacted-path>"))
                    .andExpect(jsonPath("$[0].repositoryPath").value(nullValue()));

            // Verify entity itself is not mutated
            org.junit.jupiter.api.Assertions.assertEquals(rawDetails, op.getFailureDetails());
        }

        @Test
        @DisplayName("Unix paths are removed from failureDetails in API response")
        void unixPathsRemovedFromFailureDetails() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("operator-user", List.of("OPERATOR"));

            String rawDetails = "Git repository I/O error: Cannot lock /var/lib/traceback/repo/.git/refs/heads/main";
            RemediationOperationEntity op = buildOperation(
                    UUID.randomUUID(), invId, "idem-fail-unix", RemediationOperationStatus.FAILED, Instant.now()
            );
            op.setFailureDetails(rawDetails);

            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of(op));

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].failureDetails").value("Git repository I/O error: Cannot lock <redacted-path>"));

            // Verify entity itself is not mutated
            org.junit.jupiter.api.Assertions.assertEquals(rawDetails, op.getFailureDetails());
        }

        @Test
        @DisplayName("Null failureDetails remains null in response")
        void nullFailureDetailsRemainsNull() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("viewer-user", List.of("VIEWER"));

            RemediationOperationEntity op = buildOperation(
                    UUID.randomUUID(), invId, "idem-null-fail", RemediationOperationStatus.PERSISTED, Instant.now()
            );
            op.setFailureDetails(null);

            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of(op));

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].failureDetails").value(nullValue()));

            org.junit.jupiter.api.Assertions.assertNull(op.getFailureDetails());
        }

        @Test
        @DisplayName("Non-sensitive diagnostic information is preserved while redacting credentials and paths")
        void nonSensitiveDiagnosticInfoPreserved() throws Exception {
            UUID invId = UUID.randomUUID();
            String token = keyProvider.createToken("admin-user", List.of("ADMIN"));

            String rawDetails = "Target ref refs/heads/main CAS mismatch: expected 0123456789abcdef0123456789abcdef01234567";
            RemediationOperationEntity op = buildOperation(
                    UUID.randomUUID(), invId, "idem-cas-mismatch", RemediationOperationStatus.FAILED, Instant.now()
            );
            op.setFailureDetails(rawDetails);

            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(List.of(op));

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].failureDetails").value(rawDetails));
        }

        @Test
        @DisplayName("Direct DTO conversion preserves entity immutability and sanitizes failureDetails")
        void directDtoConversionSanitizesWithoutModifyingEntity() {
            String rawDetails = "Cannot lock C:\\secured\\traceback\\repo\\.git\\refs\\heads\\main";
            RemediationOperationEntity op = new RemediationOperationEntity();
            op.setId(UUID.randomUUID());
            op.setFailureDetails(rawDetails);
            op.setRepositoryPath("C:\\secured\\traceback\\repo");

            com.traceback.api.dto.RemediationOperationResponse dto =
                    com.traceback.api.dto.RemediationOperationResponse.fromEntitySanitized(op);

            org.junit.jupiter.api.Assertions.assertNotNull(dto);
            org.junit.jupiter.api.Assertions.assertNull(dto.repositoryPath());
            org.junit.jupiter.api.Assertions.assertEquals("Cannot lock <redacted-path>", dto.failureDetails());

            // Persisted entity is 100% unchanged
            org.junit.jupiter.api.Assertions.assertEquals(rawDetails, op.getFailureDetails());
            org.junit.jupiter.api.Assertions.assertEquals("C:\\secured\\traceback\\repo", op.getRepositoryPath());
        }
    }
}
