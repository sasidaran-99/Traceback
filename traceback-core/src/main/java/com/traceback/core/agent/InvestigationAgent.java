package com.traceback.core.agent;

import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostMessage;
import com.traceback.core.agent.bifrost.BifrostRequest;
import com.traceback.core.agent.bifrost.BifrostResponse;
import com.traceback.core.agent.tavily.ExternalResearchFinding;
import com.traceback.core.agent.tavily.TavilyResearchService;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.ClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autonomous AI Investigation Agent.
 * Orchestrates technical research via Tavily, reasoning via Bifrost AI Gateway,
 * and grounds all assertions using deterministic evidence and the ClaimValidator.
 */
@Service
public class InvestigationAgent {
    private static final Logger log = LoggerFactory.getLogger(InvestigationAgent.class);

    private final BifrostClient bifrostClient;
    private final TavilyResearchService tavilyService;
    private final ClaimValidator claimValidator;
    private final EvidenceRegistry evidenceRegistry;
    private final InvestigationService investigationService;

    private final Map<UUID, InvestigationAgentReport> reportStore = new ConcurrentHashMap<>();

    public InvestigationAgent(BifrostClient bifrostClient,
                              TavilyResearchService tavilyService,
                              ClaimValidator claimValidator,
                              EvidenceRegistry evidenceRegistry,
                              InvestigationService investigationService) {
        this.bifrostClient = bifrostClient;
        this.tavilyService = tavilyService;
        this.claimValidator = claimValidator;
        this.evidenceRegistry = evidenceRegistry;
        this.investigationService = investigationService;
    }

    public InvestigationAgentReport investigate(UUID investigationId) {
        InvestigationResult result = investigationService.getInvestigation(investigationId)
                .orElseThrow(() -> new IllegalArgumentException("Investigation not found: " + investigationId));
        return investigate(result);
    }

    public InvestigationAgentReport investigate(InvestigationResult result) {
        UUID invId = result.investigationId();
        List<AgentInvestigationStep> steps = new ArrayList<>();
        int stepNum = 1;

        // Step 1: Ingest deterministic results
        steps.add(AgentInvestigationStep.of(
                stepNum++,
                "Deterministic Ingestion",
                String.format("Ingested deterministic results: %d commits analyzed, %d errors detected, %d hypotheses evaluated.",
                        result.commitsAnalyzed(), result.errorsDetected(), result.hypotheses().size()),
                Map.of("commits", result.commitsAnalyzed(), "errors", result.errorsDetected(), "hypotheses", result.hypotheses().size())
        ));

        // Step 2: External Technical Research via Tavily
        List<ExternalResearchFinding> findings = new ArrayList<>();
        if (tavilyService.isAvailable()) {
            try {
                findings.addAll(conductResearch(result));
                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "External Technical Research",
                        String.format("Retrieved %d technical research findings from Tavily.", findings.size()),
                        Map.of("findingCount", findings.size(), "queriesExecuted", findings.isEmpty() ? 0 : 1)
                ));
            } catch (Exception e) {
                log.warn("Tavily research encountered error: {}", e.getMessage());
                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "External Technical Research",
                        "Tavily research failed: " + e.getMessage() + ". Continuing with deterministic context.",
                        Map.of("error", e.getMessage())
                ));
            }
        } else {
            steps.add(AgentInvestigationStep.of(
                    stepNum++,
                    "External Technical Research",
                    "Tavily research service not configured (API key missing). Skipped external search.",
                    Collections.emptyMap()
            ));
        }

        List<String> sourceUrls = findings.stream()
                .map(ExternalResearchFinding::url)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        // Step 3: AI Reasoning via Bifrost Gateway
        String summary;
        String primaryRootCause;
        String aiReasoning;
        List<String> verificationSteps = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();

        Hypothesis topHypothesis = result.hypotheses().isEmpty() ? null : result.hypotheses().getFirst();
        String topCommitSha = getCommitSha(topHypothesis);
        ConfidenceBreakdown topBreakdown = topCommitSha != null
                ? result.confidenceBreakdownsByCommitSha().get(topCommitSha)
                : null;

        if (bifrostClient.isAvailable()) {
            try {
                BifrostRequest aiRequest = buildBifrostRequest(result, findings, topHypothesis, topBreakdown);
                BifrostResponse aiResponse = bifrostClient.chatCompletion(aiRequest);
                String completionText = aiResponse.firstContent();

                aiReasoning = completionText;
                summary = extractSection(completionText, "SUMMARY",
                        formatDefaultSummary(result, topHypothesis));
                primaryRootCause = extractSection(completionText, "ROOT CAUSE",
                        topHypothesis != null ? "Commit " + topCommitSha + ": " + topHypothesis.getSummary() : "Unknown");
                verificationSteps = extractList(completionText, "VERIFICATION STEPS",
                        generateDefaultVerificationSteps(result, topHypothesis));
                uncertainties = extractList(completionText, "UNCERTAINTIES",
                        generateDefaultUncertainties(result, topBreakdown));

                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "Bifrost AI Gateway Reasoning",
                        String.format("Synthesized root-cause reasoning via model '%s' (tokens: %d).",
                                aiResponse.model() != null ? aiResponse.model() : bifrostClient.getConfig().model(),
                                aiResponse.usage() != null ? aiResponse.usage().total_tokens() : 0),
                        Map.of("model", bifrostClient.getConfig().model())
                ));

            } catch (Exception e) {
                log.warn("Bifrost call failed, falling back to deterministic reasoning: {}", e.getMessage());
                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "Bifrost AI Gateway Reasoning",
                        "Bifrost gateway error: " + e.getMessage() + ". Executed deterministic fallback.",
                        Map.of("error", e.getMessage())
                ));
                summary = formatDefaultSummary(result, topHypothesis);
                primaryRootCause = topHypothesis != null
                        ? "Commit " + topCommitSha + " (" + topHypothesis.getSummary() + ")"
                        : "Inconclusive root cause";
                aiReasoning = formatFallbackReasoning(result, topHypothesis, topBreakdown);
                verificationSteps = generateDefaultVerificationSteps(result, topHypothesis);
                uncertainties = generateDefaultUncertainties(result, topBreakdown);
            }
        } else {
            steps.add(AgentInvestigationStep.of(
                    stepNum++,
                    "Bifrost AI Gateway Reasoning",
                    "Bifrost client not configured (API key missing). Deterministic fallback reasoning generated.",
                    Collections.emptyMap()
            ));
            summary = formatDefaultSummary(result, topHypothesis);
            primaryRootCause = topHypothesis != null
                    ? "Commit " + topCommitSha + " (" + topHypothesis.getSummary() + ")"
                    : "Inconclusive root cause";
            aiReasoning = formatFallbackReasoning(result, topHypothesis, topBreakdown);
            verificationSteps = generateDefaultVerificationSteps(result, topHypothesis);
            uncertainties = generateDefaultUncertainties(result, topBreakdown);
        }

        // Step 4: Claim Validation & Grounding against EvidenceRegistry
        List<InvestigationClaim> allClaims = new ArrayList<>(result.claims());
        if (topHypothesis != null && topCommitSha != null) {
            List<String> supIds = result.evidenceByCommitSha().getOrDefault(topCommitSha, Collections.emptyList());
            String counter = result.counterEvidenceByCommitSha().get(topCommitSha);
            List<String> contraIds = counter != null ? List.of(counter) : Collections.emptyList();

            InvestigationClaim topClaim = InvestigationClaim.create(
                    "CLAIM-AGENT-PRIMARY-" + invId.toString().substring(0, 8),
                    invId,
                    "Commit " + topCommitSha + " is the primary suspected root cause of failure.",
                    supIds,
                    contraIds
            );
            allClaims.add(topClaim);
        }

        List<InvestigationClaim> validatedClaims = allClaims.stream()
                .map(c -> claimValidator.validateClaim(c, invId, evidenceRegistry))
                .toList();

        long validCount = validatedClaims.stream().filter(c -> c.status() == ClaimValidationStatus.SUPPORTED || c.status() == ClaimValidationStatus.PARTIALLY_SUPPORTED).count();
        long unsuppCount = validatedClaims.stream().filter(c -> c.status() == ClaimValidationStatus.UNSUPPORTED || c.status() == ClaimValidationStatus.CONTRADICTED).count();

        steps.add(AgentInvestigationStep.of(
                stepNum++,
                "Evidence Grounding & Claim Validation",
                String.format("Validated %d claims against EvidenceRegistry. %d supported/partially supported, %d unsupported/contradicted.",
                        validatedClaims.size(), validCount, unsuppCount),
                Map.of("totalClaims", validatedClaims.size(), "supported", validCount, "unsupportedOrContradicted", unsuppCount)
        ));

        // Step 5: Final Verdict Determination
        AgentInvestigationStatus finalStatus = determineFinalStatus(result, topHypothesis, topBreakdown);

        steps.add(AgentInvestigationStep.of(
                stepNum,
                "Final Verdict",
                "Assigned final investigation status: " + finalStatus,
                Map.of("verdict", finalStatus.name())
        ));

        List<Hypothesis> alternativeHypotheses = result.hypotheses().size() > 1
                ? result.hypotheses().subList(1, result.hypotheses().size())
                : Collections.emptyList();

        List<String> supportingEvidenceIds = topHypothesis != null && topCommitSha != null
                ? result.evidenceByCommitSha().getOrDefault(topCommitSha, Collections.emptyList())
                : Collections.emptyList();

        List<String> contradictoryEvidenceIds = topHypothesis != null && topCommitSha != null && result.counterEvidenceByCommitSha().containsKey(topCommitSha)
                ? List.of(result.counterEvidenceByCommitSha().get(topCommitSha))
                : Collections.emptyList();

        InvestigationAgentReport report = new InvestigationAgentReport(
                "REP-" + UUID.randomUUID().toString().substring(0, 8),
                invId,
                summary,
                primaryRootCause,
                alternativeHypotheses,
                supportingEvidenceIds,
                contradictoryEvidenceIds,
                topBreakdown,
                aiReasoning,
                findings,
                sourceUrls,
                verificationSteps,
                uncertainties,
                finalStatus,
                validatedClaims,
                steps,
                Instant.now()
        );

        reportStore.put(invId, report);
        log.info("Agent report {} generated for investigation {} with status {}", report.reportId(), invId, finalStatus);
        return report;
    }

    public Optional<InvestigationAgentReport> getReport(UUID investigationId) {
        return Optional.ofNullable(reportStore.get(investigationId));
    }

    public List<ExternalResearchFinding> getResearchFindings(UUID investigationId) {
        return getReport(investigationId).map(InvestigationAgentReport::externalResearchFindings).orElse(Collections.emptyList());
    }

    public List<AgentInvestigationStep> getInvestigationSteps(UUID investigationId) {
        return getReport(investigationId).map(InvestigationAgentReport::investigationSteps).orElse(Collections.emptyList());
    }

    private String getCommitSha(Hypothesis hypothesis) {
        if (hypothesis == null || hypothesis.getCandidateRootCause() == null) {
            return null;
        }
        return hypothesis.getCandidateRootCause().getEntityId();
    }

    private List<ExternalResearchFinding> conductResearch(InvestigationResult result) {
        List<ExternalResearchFinding> findings = new ArrayList<>();
        ParsedException pe = result.primaryException();

        if (pe != null && pe.exceptionType() != null) {
            String q1 = pe.exceptionType();
            if (pe.message() != null && !pe.message().isBlank()) {
                String cleanMsg = pe.message().replaceAll("[\r\n]+", " ");
                if (cleanMsg.length() > 80) {
                    cleanMsg = cleanMsg.substring(0, 80);
                }
                q1 = q1 + " " + cleanMsg;
            }
            findings.addAll(tavilyService.research(q1, "Java Spring Boot failure"));
        }

        return findings;
    }

    private BifrostRequest buildBifrostRequest(InvestigationResult result,
                                               List<ExternalResearchFinding> findings,
                                               Hypothesis topHypothesis,
                                               ConfidenceBreakdown topBreakdown) {
        String systemPrompt = """
                You are the Traceback Autonomous Investigation Agent.
                Your task is to analyze an application incident using ONLY the verified evidence provided.
                
                CRITICAL INVARIANTS:
                1. Cite existing evidence IDs (e.g. EVD-...) from the evidence list.
                2. NEVER hallucinate commit SHAs, file names, or evidence identifiers.
                3. Ground all confidence assessments strictly in the deterministic breakdown: S = T + O + A - C.
                4. Highlight any contradictory evidence or penalties.
                5. Provide your analysis in structured sections:
                   SUMMARY: ...
                   ROOT CAUSE: ...
                   REASONING: ...
                   VERIFICATION STEPS:
                   - ...
                   UNCERTAINTIES:
                   - ...
                """;

        StringBuilder sb = new StringBuilder();
        sb.append("INVESTIGATION CONTEXT:\n");
        sb.append("Investigation ID: ").append(result.investigationId()).append("\n");
        sb.append("Service: ").append(result.serviceName()).append("\n");
        sb.append("Environment: ").append(result.environment()).append("\n");

        if (result.primaryException() != null) {
            sb.append("\nPRIMARY EXCEPTION:\n");
            sb.append("Type: ").append(result.primaryException().exceptionType()).append("\n");
            sb.append("Message: ").append(result.primaryException().message()).append("\n");
            if (result.primaryException().frames() != null) {
                sb.append("Top frames:\n");
                for (StackTraceFrame frame : result.primaryException().frames().stream().limit(5).toList()) {
                    sb.append("  - ").append(frame.declaringClass()).append(".").append(frame.methodName())
                            .append(" (").append(frame.fileName()).append(":").append(frame.lineNumber()).append(")\n");
                }
            }
        }

        sb.append("\nREGISTERED EVIDENCE ITEMS:\n");
        for (EvidenceItem item : result.evidenceItems()) {
            sb.append(String.format("  [%s] %s: %s (SHA-256: %s)\n",
                    item.evidenceId(), item.evidenceType(), item.summary(),
                    item.integrityHash() != null ? item.integrityHash() : "none"));
        }

        if (topHypothesis != null) {
            String sha = getCommitSha(topHypothesis);
            sb.append("\nLEADING DETERMINISTIC HYPOTHESIS:\n");
            sb.append("Commit: ").append(sha).append("\n");
            sb.append("Confidence: ").append(topHypothesis.getConfidenceScore()).append("\n");
            sb.append("Explanation: ").append(topHypothesis.getSummary()).append("\n");
            if (topBreakdown != null) {
                sb.append("Breakdown: T=").append(topBreakdown.temporalPoints())
                        .append(", O=").append(topBreakdown.overlapPoints())
                        .append(", A=").append(topBreakdown.agreementPoints())
                        .append(", C=").append(topBreakdown.contradictionPenalty())
                        .append(", Normalized=").append(topBreakdown.normalizedScore()).append("\n");
                sb.append("Positive factors: ").append(topBreakdown.scoreReasons()).append("\n");
                sb.append("Penalties: ").append(topBreakdown.penaltyReasons()).append("\n");
            }
        }

        if (!findings.isEmpty()) {
            sb.append("\nEXTERNAL TECHNICAL RESEARCH FINDINGS:\n");
            for (ExternalResearchFinding f : findings) {
                sb.append("  - [").append(f.findingId()).append("] ").append(f.title())
                        .append(" (").append(f.url()).append(")\n    Snippet: ")
                        .append(f.snippet().replaceAll("[\r\n]+", " ")).append("\n");
            }
        }

        List<BifrostMessage> messages = List.of(
                BifrostMessage.system(systemPrompt),
                BifrostMessage.user(sb.toString())
        );

        return BifrostRequest.of(bifrostClient.getConfig().model(), messages);
    }

    private AgentInvestigationStatus determineFinalStatus(InvestigationResult result,
                                                          Hypothesis topHypothesis,
                                                          ConfidenceBreakdown topBreakdown) {
        if (topHypothesis == null || !result.evidenceSufficient()) {
            return AgentInvestigationStatus.INSUFFICIENT_EVIDENCE;
        }

        double conf = topHypothesis.getConfidenceScore();
        boolean hasPenalty = topBreakdown != null && topBreakdown.contradictionPenalty() > 0;

        if (conf >= 0.70 && !hasPenalty) {
            return AgentInvestigationStatus.CONFIRMED;
        } else if (conf >= 0.40) {
            return AgentInvestigationStatus.PROBABLE;
        } else {
            return AgentInvestigationStatus.INSUFFICIENT_EVIDENCE;
        }
    }

    private String formatDefaultSummary(InvestigationResult result, Hypothesis topHypothesis) {
        String exc = result.primaryException() != null ? result.primaryException().exceptionType() : "Unknown error";
        String sha = getCommitSha(topHypothesis);
        if (topHypothesis != null && sha != null) {
            return String.format("Investigation identified commit %s as the most probable cause of %s in %s service (confidence: %.2f).",
                    sha, exc, result.serviceName(), topHypothesis.getConfidenceScore());
        }
        return String.format("Investigation into %s in %s service is inconclusive due to insufficient evidence.",
                exc, result.serviceName());
    }

    private String formatFallbackReasoning(InvestigationResult result, Hypothesis topHypothesis, ConfidenceBreakdown topBreakdown) {
        StringBuilder sb = new StringBuilder();
        sb.append("Deterministic Fallback Analysis:\n");
        sb.append(String.format("Evaluated %d commits against %d observed error logs. ",
                result.commitsAnalyzed(), result.errorsDetected()));
        String sha = getCommitSha(topHypothesis);
        if (topHypothesis != null && sha != null) {
            sb.append(String.format("Commit %s emerged as primary hypothesis with normalized confidence score %.2f. ",
                    sha, topHypothesis.getConfidenceScore()));
            if (topBreakdown != null) {
                sb.append(String.format("Scoring breakdown: Temporal points: %.1f, Overlap points: %.1f, Agreement points: %.1f, Contradiction penalties: %.1f. ",
                        topBreakdown.temporalPoints(), topBreakdown.overlapPoints(), topBreakdown.agreementPoints(), topBreakdown.contradictionPenalty()));
                if (!topBreakdown.penaltyReasons().isEmpty()) {
                    sb.append("Identified penalties: ").append(String.join("; ", topBreakdown.penaltyReasons())).append(". ");
                }
            }
        } else {
            sb.append("No commits exhibited sufficient temporal proximity or code overlap to establish a confident hypothesis.");
        }
        return sb.toString();
    }

    private List<String> generateDefaultVerificationSteps(InvestigationResult result, Hypothesis topHypothesis) {
        List<String> steps = new ArrayList<>();
        String sha = getCommitSha(topHypothesis);
        if (topHypothesis != null && sha != null) {
            steps.add(String.format("Inspect diff of commit %s and review modified classes/configuration.", sha));
            steps.add("Check runtime connection pool / resource saturation metrics around the failure time.");
        }
        if (result.primaryException() != null) {
            steps.add(String.format("Reproduce '%s' in a staging environment with the suspect commit reverted.",
                    result.primaryException().exceptionType()));
        }
        steps.add("Verify health check and active database connection counts during peak load.");
        return steps;
    }

    private List<String> generateDefaultUncertainties(InvestigationResult result, ConfidenceBreakdown topBreakdown) {
        List<String> uncertainties = new ArrayList<>();
        if (topBreakdown != null && !topBreakdown.penaltyReasons().isEmpty()) {
            uncertainties.addAll(topBreakdown.penaltyReasons());
        }
        if (!result.evidenceSufficient()) {
            uncertainties.add("Evidence sample size is limited; additional logs or trace data are recommended.");
        }
        if (uncertainties.isEmpty()) {
            uncertainties.add("No critical contradictions detected among analyzed evidence.");
        }
        return uncertainties;
    }

    private String extractSection(String text, String header, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        int idx = text.toUpperCase().indexOf(header.toUpperCase() + ":");
        if (idx == -1) return fallback;
        int start = idx + header.length() + 1;
        int end = text.indexOf("\n\n", start);
        if (end == -1) {
            end = text.indexOf("\n#", start);
        }
        String section = end != -1 ? text.substring(start, end) : text.substring(start);
        String trimmed = section.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private List<String> extractList(String text, String header, List<String> fallback) {
        if (text == null || text.isBlank()) return fallback;
        int idx = text.toUpperCase().indexOf(header.toUpperCase() + ":");
        if (idx == -1) return fallback;
        int start = idx + header.length() + 1;
        int end = text.indexOf("\n\n", start);
        if (end == -1) {
            end = text.indexOf("\n#", start);
        }
        String section = end != -1 ? text.substring(start, end) : text.substring(start);
        List<String> items = new ArrayList<>();
        for (String line : section.split("[\r\n]+")) {
            String clean = line.replaceFirst("^[-*\\d.]+\\s*", "").trim();
            if (!clean.isBlank()) {
                items.add(clean);
            }
        }
        return items.isEmpty() ? fallback : items;
    }
}
