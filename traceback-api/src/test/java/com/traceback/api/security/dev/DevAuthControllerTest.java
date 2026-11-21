package com.traceback.api.security.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.dto.DevTokenRequest;
import com.traceback.core.remediation.RemediationAdminService;
import com.traceback.core.remediation.RemediationExecutionCoordinator;
import com.traceback.core.remediation.RemediationJournalService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
@TestPropertySource(properties = {
        "traceback.security.dev-auth.enabled=true"
})
class DevAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

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

    @Nested
    @DisplayName("Predefined Personas Token Issuance")
    class PredefinedPersonasTests {

        @Test
        @DisplayName("POST /api/dev/auth/token - ALICE_ADMIN returns valid RS256 token with ROLE_ADMIN")
        void aliceAdminToken() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            MvcResult result = mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.subject").value("alice-admin"))
                    .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                    .andReturn();

            String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
            Jwt decoded = jwtDecoder.decode(token);
            assertEquals("alice-admin", decoded.getSubject());
            assertEquals(List.of("ADMIN"), decoded.getClaimAsStringList("roles"));

            // Verify the token can authenticate to ADMIN-protected endpoint
            UUID invId = UUID.randomUUID();
            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /api/dev/auth/token - BOB_ADMIN returns valid RS256 token with distinct subject bob-admin")
        void bobAdminToken() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.BOB_ADMIN);

            MvcResult result = mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("bob-admin"))
                    .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                    .andReturn();

            String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
            Jwt decoded = jwtDecoder.decode(token);
            assertEquals("bob-admin", decoded.getSubject());
            assertEquals(List.of("ADMIN"), decoded.getClaimAsStringList("roles"));
        }

        @Test
        @DisplayName("POST /api/dev/auth/token - CHARLIE_OPERATOR returns token with ROLE_OPERATOR")
        void charlieOperatorToken() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.CHARLIE_OPERATOR);

            MvcResult result = mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("charlie-operator"))
                    .andExpect(jsonPath("$.roles[0]").value("OPERATOR"))
                    .andReturn();

            String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
            Jwt decoded = jwtDecoder.decode(token);
            assertEquals("charlie-operator", decoded.getSubject());

            // Operator can discover remediations
            UUID invId = UUID.randomUUID();
            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            // Operator CANNOT freeze refs (ADMIN only)
            mockMvc.perform(post("/api/remediations/refs/freeze")
                            .param("refName", "refs/heads/main")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/dev/auth/token - DANA_VIEWER returns token with ROLE_VIEWER")
        void danaViewerToken() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.DANA_VIEWER);

            MvcResult result = mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("dana-viewer"))
                    .andExpect(jsonPath("$.roles[0]").value("VIEWER"))
                    .andReturn();

            String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
            Jwt decoded = jwtDecoder.decode(token);
            assertEquals("dana-viewer", decoded.getSubject());

            // Viewer can discover remediations (read-only)
            UUID invId = UUID.randomUUID();
            when(operationRepository.findByInvestigationIdOrderByCreatedAtDesc(invId))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/remediations")
                            .param("investigationId", invId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            // Viewer CANNOT propose remediations (requires ADMIN or OPERATOR)
            mockMvc.perform(post("/api/remediations/proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"idempotencyKey\":\"k1\",\"repositoryPath\":\"/repo\",\"targetRef\":\"refs/heads/main\",\"expectedOldSha\":\"0123456789abcdef0123456789abcdef01234567\",\"proposedNewSha\":\"abcdef0123456789abcdef0123456789abcdef01\",\"operationType\":\"BRANCH_PATCH_PROMOTION\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/dev/auth/token - Supports flexible casing and kebab-case persona names")
        void flexiblePersonaNaming() throws Exception {
            mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"persona\":\"alice-admin\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("alice-admin"));

            mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"persona\":\"charlie_operator\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("charlie-operator"));
        }
    }

    @Nested
    @DisplayName("Input Validation and Tamper Resistance")
    class ValidationAndTamperResistanceTests {

        @Test
        @DisplayName("POST /api/dev/auth/token with invalid persona string returns 400 Bad Request")
        void invalidPersonaStringReturnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"persona\":\"ROOT_SUPERUSER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"));
        }

        @Test
        @DisplayName("POST /api/dev/auth/token with missing persona returns 400 Bad Request")
        void missingPersonaReturnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("POST /api/dev/auth/token ignores client-supplied roles and subjects")
        void ignoresClientSuppliedRolesAndSubjects() throws Exception {
            // Attempt to inject subject "root" and role "SUPERUSER" alongside ALICE_ADMIN
            String maliciousJson = "{\"persona\":\"ALICE_ADMIN\",\"subject\":\"root\",\"roles\":[\"ROOT\",\"SUPERADMIN\"]}";

            MvcResult result = mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(maliciousJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("alice-admin"))
                    .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                    .andReturn();

            String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
            Jwt decoded = jwtDecoder.decode(token);
            assertEquals("alice-admin", decoded.getSubject());
            assertEquals(List.of("ADMIN"), decoded.getClaimAsStringList("roles"));
        }

        @Test
        @DisplayName("POST /api/dev/auth/token from non-loopback IP returns 403 Forbidden")
        void nonLoopbackAddressReturnsForbidden() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.25");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("Tampered token signature is rejected by JwtDecoder")
        void tamperedTokenRejected() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            MvcResult result = mockMvc.perform(post("/api/dev/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();

            String validToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
            String[] parts = validToken.split("\\.");
            // Tamper with the signature portion
            String tamperedSignature = parts[2].substring(0, parts[2].length() - 4) + "XXXX";
            String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

            assertThrows(BadJwtException.class, () -> jwtDecoder.decode(tamperedToken));
        }
    }

    @Nested
    @DisplayName("Network Boundary and Proxy Header Rejection (P1.1 Hardening)")
    class NetworkBoundaryAndProxyRejectionTests {

        @Test
        @DisplayName("Direct non-loopback requests (e.g. 10.0.0.1) are rejected with 403 Forbidden")
        void directNonLoopbackRejected() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("10.0.0.1");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("loopback connections")));
        }

        @Test
        @DisplayName("Loopback request with X-Forwarded-For header is rejected with 403 Forbidden")
        void loopbackWithXForwardedForRejected() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("127.0.0.1");
                                return req;
                            })
                            .header("X-Forwarded-For", "203.0.113.195")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("proxied requests are forbidden")));
        }

        @Test
        @DisplayName("Loopback request with Forwarded header is rejected with 403 Forbidden")
        void loopbackWithForwardedRejected() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("127.0.0.1");
                                return req;
                            })
                            .header("Forwarded", "for=198.51.100.10;proto=http")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("proxied requests are forbidden")));
        }

        @Test
        @DisplayName("Loopback request with X-Real-IP header is rejected with 403 Forbidden")
        void loopbackWithXRealIpRejected() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("127.0.0.1");
                                return req;
                            })
                            .header("X-Real-IP", "198.51.100.10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("proxied requests are forbidden")));
        }

        @Test
        @DisplayName("Loopback request with X-Forwarded-For pointing to loopback is still rejected with 403 Forbidden")
        void loopbackWithLoopbackInXForwardedForRejected() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("127.0.0.1");
                                return req;
                            })
                            .header("X-Forwarded-For", "127.0.0.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("proxied requests are forbidden")));
        }

        @Test
        @DisplayName("Missing remote address (null) fails closed with 403 Forbidden")
        void missingRemoteAddressFailsClosed() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr(null);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("remote address is missing")));
        }

        @Test
        @DisplayName("Blank remote address fails closed with 403 Forbidden")
        void blankRemoteAddressFailsClosed() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("   ");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Malformed remote address fails closed with 403 Forbidden")
        void malformedRemoteAddressFailsClosed() throws Exception {
            DevTokenRequest request = new DevTokenRequest(DevPersona.ALICE_ADMIN);

            mockMvc.perform(post("/api/dev/auth/token")
                            .with(req -> {
                                req.setRemoteAddr("not.a.valid.ip.address.format-999");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Nested
    @DisplayName("Development CORS Boundary Verification (P2.2 Hardening)")
    class CorsBoundaryTests {

        @Test
        @DisplayName("Exact allowed origin http://localhost:5173 succeeds CORS preflight")
        void allowedLocalhost5173Succeeds() throws Exception {
            mockMvc.perform(options("/api/dev/auth/token")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        }

        @Test
        @DisplayName("Exact allowed origin http://127.0.0.1:5173 succeeds CORS preflight")
        void allowed127001Port5173Succeeds() throws Exception {
            mockMvc.perform(options("/api/dev/auth/token")
                            .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5173"));
        }

        @Test
        @DisplayName("Unauthorized origin on different port (http://localhost:3000) fails CORS preflight")
        void unauthorizedPort3000FailsCors() throws Exception {
            mockMvc.perform(options("/api/dev/auth/token")
                            .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("Unauthorized external origin (https://malicious.external.org) fails CORS preflight")
        void unauthorizedExternalDomainFailsCors() throws Exception {
            mockMvc.perform(options("/api/dev/auth/token")
                            .header(HttpHeaders.ORIGIN, "https://malicious.external.org")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }
}
