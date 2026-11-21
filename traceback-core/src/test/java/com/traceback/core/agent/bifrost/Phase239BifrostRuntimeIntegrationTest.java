package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.agent.tavily.DefaultTavilyResearchService;
import com.traceback.core.agent.tavily.TavilyConfig;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.Event;
import com.traceback.core.domain.EventSource;
import com.traceback.core.domain.EventType;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2.39: Bifrost Runtime Integration Verification Test.
 * Validates Bifrost gateway integration with Google Gemini:
 * - Base URL: http://localhost:8181/v1 (not 8080)
 * - Header: x-bf-vk
 * - Model: gemini-3.8-flash
 * - Timeout: 180 seconds default
 * - Response parsing & retry logic
 * - Deterministic verdict & claim validation preservation
 */
class Phase239BifrostRuntimeIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("1. Configuration loading: defaults must point to port 8181, gemini-3.8-flash, and 180s timeout")
    void testConfigurationLoadingDefaults() {
        BifrostConfig config = new BifrostConfig(null, "sk-bf-test-key", null, null, 2);

        assertThat(config.baseUrl()).isEqualTo("http://localhost:8181/v1");
        assertThat(config.model()).isEqualTo("gemini-3.8-flash");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(config.maxRetries()).isEqualTo(2);
        assertThat(config.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("2. HTTP request construction: verifies URL is http://localhost:8181/v1/chat/completions")
    void testHttpRequestUrlConstruction() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
                {"id":"test-id","choices":[{"index":0,"message":{"role":"assistant","content":"OK"}}]}
                """);
        doReturn(mockResponse).when(mockHttp).send(any(), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("hello"))));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttp).send(captor.capture(), any());

        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).isEqualTo("http://localhost:8181/v1/chat/completions");
    }

    @Test
    @DisplayName("3. Authentication header: must use x-bf-vk without logging key, and omit Authorization")
    void testXbfvkAuthenticationHeader() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
                {"id":"test-id","choices":[{"index":0,"message":{"role":"assistant","content":"OK"}}]}
                """);
        doReturn(mockResponse).when(mockHttp).send(any(), any());

        String secretVirtualKey = "sk-bf-99af1cf5-6400-4698-9b44-4b23bf53fa65";
        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", secretVirtualKey, "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("hello"))));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttp).send(captor.capture(), any());

        HttpRequest request = captor.getValue();
        assertThat(request.headers().firstValue("x-bf-vk")).contains(secretVirtualKey);
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(config.maskedApiKey()).isEqualTo("sk-bf-...fa65");
        assertThat(config.maskedApiKey()).doesNotContain("99af1cf5-6400");
    }

    @Test
    @DisplayName("4. Model selection: verifies request payload uses gemini-3.8-flash")
    void testSelectedModelIsGeminiFlash() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
                {"id":"test-id","model":"gemini-3.8-flash","choices":[{"index":0,"message":{"role":"assistant","content":"OK"}}]}
                """);
        doReturn(mockResponse).when(mockHttp).send(any(), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        BifrostRequest req = BifrostRequest.of(config.model(), List.of(BifrostMessage.user("hello")));
        BifrostResponse resp = client.chatCompletion(req);

        assertThat(req.model()).isEqualTo("gemini-3.8-flash");
        assertThat(resp.model()).isEqualTo("gemini-3.8-flash");
    }

    @Test
    @DisplayName("5. Response parsing: parses id, choices, usage, and content correctly")
    void testSuccessfulResponseParsing() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
                {
                  "id": "vbv5aq6VNe2Q694PqN7yyQ4",
                  "model": "gemini-3.8-flash",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "SUMMARY: Database connection pool exhausted under load.\\nROOT CAUSE: Commit a1b2c3d reduced pool size to 2.\\nVERIFICATION STEPS:\\n- Check pool metrics\\nUNCERTAINTIES:\\n- Peak query load"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 125,
                    "completion_tokens": 68,
                    "total_tokens": 193
                  }
                }
                """);
        doReturn(mockResponse).when(mockHttp).send(any(), any());

        BifrostConfig config = new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2);
        DefaultBifrostClient client = new DefaultBifrostClient(config, mockHttp, objectMapper);

        BifrostResponse resp = client.chatCompletion(BifrostRequest.of("gemini-3.8-flash", List.of(BifrostMessage.user("analyze"))));

        assertThat(resp.id()).isEqualTo("vbv5aq6VNe2Q694PqN7yyQ4");
        assertThat(resp.firstContent()).contains("SUMMARY: Database connection pool exhausted under load.");
        assertThat(resp.usage().total_tokens()).isEqualTo(193);
    }

    @Test
    @DisplayName("6. InvestigationAgent integrates Bifrost synthesis with SUCCESS status and preserves deterministic verdict")
    void testInvestigationAgentSuccessIntegration() {
        BifrostClient mockBifrost = mock(BifrostClient.class);
        when(mockBifrost.isAvailable()).thenReturn(true);
        when(mockBifrost.getConfig()).thenReturn(new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2));

        String mockAiText = """
                SUMMARY: Verified regression caused by commit c123456 modifying OrderService.java.
                ROOT CAUSE: Missing null check on order.getShippingAddress().
                VERIFICATION STEPS:
                - Inspect OrderService.java line 42
                UNCERTAINTIES:
                - None
                """;

        BifrostResponse mockResp = new BifrostResponse(
                "chatcmpl-test",
                "chat.completion",
                1700000000L,
                "gemini-3.8-flash",
                List.of(new BifrostResponse.Choice(0, BifrostMessage.assistant(mockAiText), "stop")),
                new BifrostResponse.Usage(50, 50, 100)
        );
        when(mockBifrost.chatCompletion(any())).thenReturn(mockResp);

        ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry();
        InvestigationResult invResult = createSampleInvestigationResult(registry, 0.706, true);

        InvestigationAgent agent = new InvestigationAgent(
                mockBifrost,
                new DefaultTavilyResearchService(TavilyConfig.fromEnvironment()),
                new DeterministicClaimValidator(),
                registry,
                mock(InvestigationService.class)
        );

        InvestigationAgentReport report = agent.investigate(invResult);

        // Core integrity: Deterministic verdict PROBABLE is preserved
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
        assertThat(report.primarySuspectedRootCause()).contains("Commit c123456");
        assertThat(report.aiReasoningSummary()).contains("Missing null check");

        // Verify Bifrost AI Gateway Reasoning step recorded with SUCCESS status
        AgentInvestigationStep bifrostStep = report.investigationSteps().stream()
                .filter(s -> "Bifrost AI Gateway Reasoning".equals(s.stepName()))
                .findFirst()
                .orElseThrow();

        assertThat(bifrostStep.metadata().get("status")).isEqualTo("SUCCESS");
        assertThat(bifrostStep.metadata().get("model")).isEqualTo("gemini-3.8-flash");
    }

    @Test
    @DisplayName("7. Deterministic fallback when Bifrost is unavailable: produces UNCONFIGURED status and valid dossier")
    void testDeterministicFallbackWhenUnavailable() {
        BifrostClient mockBifrost = mock(BifrostClient.class);
        when(mockBifrost.isAvailable()).thenReturn(false);
        when(mockBifrost.getConfig()).thenReturn(new BifrostConfig("http://localhost:8181/v1", null, "gemini-3.8-flash", Duration.ofSeconds(180), 2));

        ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry();
        InvestigationResult invResult = createSampleInvestigationResult(registry, 0.706, true);

        InvestigationAgent agent = new InvestigationAgent(
                mockBifrost,
                new DefaultTavilyResearchService(TavilyConfig.fromEnvironment()),
                new DeterministicClaimValidator(),
                registry,
                mock(InvestigationService.class)
        );

        InvestigationAgentReport report = agent.investigate(invResult);

        // Deterministic verdict is identical
        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
        assertThat(report.aiReasoningSummary()).contains("Deterministic Fallback Analysis");

        // Verify Bifrost step recorded with UNCONFIGURED status
        AgentInvestigationStep bifrostStep = report.investigationSteps().stream()
                .filter(s -> "Bifrost AI Gateway Reasoning".equals(s.stepName()))
                .findFirst()
                .orElseThrow();

        assertThat(bifrostStep.metadata().get("status")).isEqualTo("UNCONFIGURED");
    }

    @Test
    @DisplayName("8. Deterministic fallback on Bifrost HTTP 500 error: records REQUEST_FAILURE and fallback reasoning")
    void testDeterministicFallbackOnBifrostError() {
        BifrostClient mockBifrost = mock(BifrostClient.class);
        when(mockBifrost.isAvailable()).thenReturn(true);
        when(mockBifrost.getConfig()).thenReturn(new BifrostConfig("http://localhost:8181/v1", "sk-bf-key", "gemini-3.8-flash", Duration.ofSeconds(180), 2));
        when(mockBifrost.chatCompletion(any())).thenThrow(new BifrostException("Upstream internal server error", 500));

        ConcreteEvidenceRegistry registry = new ConcreteEvidenceRegistry();
        InvestigationResult invResult = createSampleInvestigationResult(registry, 0.706, true);

        InvestigationAgent agent = new InvestigationAgent(
                mockBifrost,
                new DefaultTavilyResearchService(TavilyConfig.fromEnvironment()),
                new DeterministicClaimValidator(),
                registry,
                mock(InvestigationService.class)
        );

        InvestigationAgentReport report = agent.investigate(invResult);

        assertThat(report.finalStatus()).isEqualTo(AgentInvestigationStatus.PROBABLE);
        AgentInvestigationStep bifrostStep = report.investigationSteps().stream()
                .filter(s -> "Bifrost AI Gateway Reasoning".equals(s.stepName()))
                .findFirst()
                .orElseThrow();

        assertThat(bifrostStep.metadata().get("status")).isEqualTo("REQUEST_FAILURE");
    }

    private InvestigationResult createSampleInvestigationResult(ConcreteEvidenceRegistry registry, double score, boolean sufficient) {
        UUID invId = UUID.randomUUID();
        EvidenceItem item = EvidenceItem.create(
                "EVD-GIT-c123456",
                EvidenceType.COMMIT,
                "GIT",
                "c123456",
                Instant.now(),
                "Fix order processing",
                Map.of("sha", "c123456")
        );
        registry.registerItem(item, invId);

        Event commitEvent = new Event(UUID.randomUUID(), EventType.COMMIT, EventSource.GITHUB,
                "order-service", "prod", Instant.now(), "c123456", "{}");
        Incident incident = new Incident();
        incident.setId(invId);

        Hypothesis hyp = new Hypothesis(
                UUID.randomUUID(),
                incident,
                commitEvent,
                1,
                score,
                HypothesisStatus.INVESTIGATING,
                "Commit c123456 introduced NPE in OrderService.java",
                "Check null handling in order processing",
                null
        );
        hyp.setSupportingEvidenceIds(List.of("EVD-GIT-c123456"));

        ConfidenceBreakdown breakdown = ConfidenceBreakdown.calculate(
                30.0, 30.0, 0.0, 0.0,
                List.of("Temporal match", "Path overlap"),
                Collections.emptyList()
        );

        InvestigationClaim claim = InvestigationClaim.create(
                "CLAIM-1",
                invId,
                "Commit c123456 modified OrderService.java",
                List.of("EVD-GIT-c123456"),
                Collections.emptyList()
        );

        ParsedException pe = new ParsedException(
                "NullPointerException",
                "Cannot invoke method on null",
                List.of(StackTraceFrame.of("com.traceback.OrderService", "processOrder", "OrderService.java", 42)),
                null
        );

        return new InvestigationResult(
                invId,
                "order-service",
                "prod",
                Instant.now(),
                Instant.now(),
                "COMPLETED",
                1, 1,
                pe,
                Collections.emptyList(),
                List.of(item),
                Collections.emptyList(),
                List.of(hyp),
                Map.of("c123456", List.of("EVD-GIT-c123456")),
                Collections.emptyMap(),
                Map.of("c123456", breakdown),
                List.of(claim),
                sufficient,
                InvestigationMode.RUNTIME_FAILURE
        );
    }
}
