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

        // Ensure all evidence items from deterministic result are available in the active registry
        if (result.evidenceItems() != null) {
            for (EvidenceItem item : result.evidenceItems()) {
                evidenceRegistry.registerItem(item);
            }
        }

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

        Hypothesis topHypothesis = result.hypotheses().isEmpty() ? null : result.hypotheses().getFirst();
        String topCommitSha = getCommitSha(topHypothesis);
        ConfidenceBreakdown topBreakdown = topCommitSha != null
                ? result.confidenceBreakdownsByCommitSha().get(topCommitSha)
                : null;

        // Authoritative Final Verdict Determination (deterministic foundation)
        AgentInvestigationStatus finalStatus = determineFinalStatus(result, topHypothesis, topBreakdown);

        // Step 3: AI Reasoning via Bifrost Gateway (or Deterministic Fallback)
        String summary;
        String primaryRootCause;
        String aiReasoning;
        List<String> verificationSteps = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();

        if (bifrostClient.isAvailable()) {
            try {
                BifrostRequest aiRequest = buildBifrostRequest(result, findings, topHypothesis, topBreakdown);
                BifrostResponse aiResponse = bifrostClient.chatCompletion(aiRequest);
                String completionText = aiResponse.firstContent();

                aiReasoning = completionText;
                summary = extractSection(completionText, "SUMMARY",
                        formatDefaultSummary(result, topHypothesis, finalStatus));

                // Guard against AI hallucinated verdict overrides:
                if (isWorkingTreeAnomaly(topHypothesis)) {
                    summary = topHypothesis.getSummary();
                    primaryRootCause = "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.";
                } else if (finalStatus == AgentInvestigationStatus.INSUFFICIENT_EVIDENCE) {
                    summary = formatDefaultSummary(result, topHypothesis, finalStatus);
                    primaryRootCause = "None: Available telemetry is insufficient to establish a primary cause.";
                } else if (finalStatus == AgentInvestigationStatus.PROBABLE) {
                    if (summary.toLowerCase().contains("confirmed") || summary.toLowerCase().contains("directly triggered")) {
                        summary = formatDefaultSummary(result, topHypothesis, finalStatus);
                    }
                    primaryRootCause = topHypothesis != null ? topHypothesis.getSummary() : "Inconclusive root cause";
                } else {
                    primaryRootCause = extractSection(completionText, "ROOT CAUSE",
                            topHypothesis != null ? topHypothesis.getSummary() : "Unknown");
                }

                verificationSteps = extractList(completionText, "VERIFICATION STEPS",
                        generateDefaultVerificationSteps(result, topHypothesis));
                uncertainties = extractList(completionText, "UNCERTAINTIES",
                        generateDefaultUncertainties(result, topBreakdown, finalStatus));

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
                summary = formatDefaultSummary(result, topHypothesis, finalStatus);
                primaryRootCause = isWorkingTreeAnomaly(topHypothesis)
                        ? "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established."
                        : (topHypothesis != null ? topHypothesis.getSummary() : "None: Telemetry is insufficient to establish a primary cause.");
                aiReasoning = formatFallbackReasoning(result, topHypothesis, topBreakdown, finalStatus);
                verificationSteps = generateDefaultVerificationSteps(result, topHypothesis);
                uncertainties = generateDefaultUncertainties(result, topBreakdown, finalStatus);
            }
        } else {
            steps.add(AgentInvestigationStep.of(
                    stepNum++,
                    "Bifrost AI Gateway Reasoning",
                    "Bifrost client not configured (API key missing). Deterministic fallback reasoning generated.",
                    Collections.emptyMap()
            ));
            summary = formatDefaultSummary(result, topHypothesis, finalStatus);
            primaryRootCause = isWorkingTreeAnomaly(topHypothesis)
                    ? "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established."
                    : (topHypothesis != null ? topHypothesis.getSummary() : "None: Telemetry is insufficient to establish a primary cause.");
            aiReasoning = formatFallbackReasoning(result, topHypothesis, topBreakdown, finalStatus);
            verificationSteps = generateDefaultVerificationSteps(result, topHypothesis);
            uncertainties = generateDefaultUncertainties(result, topBreakdown, finalStatus);
        }

        // Step 4: Claim Validation & Grounding against EvidenceRegistry
        List<InvestigationClaim> allClaims = new ArrayList<>(result.claims());
        if (topHypothesis != null && (topCommitSha != null || isWorkingTreeAnomaly(topHypothesis))) {
            List<String> supIds = isWorkingTreeAnomaly(topHypothesis)
                    ? topHypothesis.getSupportingEvidenceIds()
                    : result.evidenceByCommitSha().getOrDefault(topCommitSha, Collections.emptyList());
            String counter = topCommitSha != null ? result.counterEvidenceByCommitSha().get(topCommitSha) : null;
            List<String> contraIds = counter != null ? List.of(counter) : (isWorkingTreeAnomaly(topHypothesis) ? topHypothesis.getContradictingEvidenceIds() : Collections.emptyList());

            String claimStatement;
            if (isWorkingTreeAnomaly(topHypothesis)) {
                claimStatement = topHypothesis.getSummary();
            } else if (finalStatus == AgentInvestigationStatus.CONFIRMED) {
                claimStatement = "Commit " + topCommitSha + " is the confirmed root cause of failure.";
            } else if (finalStatus == AgentInvestigationStatus.PROBABLE) {
                claimStatement = "Commit " + topCommitSha + " is a probable contributing change for " + result.serviceName() + " failure.";
            } else {
                claimStatement = "Commit " + topCommitSha + " shows insufficient evidence to confirm root cause.";
            }

            InvestigationClaim topClaim = InvestigationClaim.create(
                    "CLAIM-AGENT-PRIMARY-" + invId.toString().substring(0, 8),
                    invId,
                    claimStatement,
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

        // Step 5: Record Final Authoritative Verdict in Audit Trail
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

    public List<InvestigationAgentReport> getAllReports() {
        return List.copyOf(reportStore.values());
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
        if (hypothesis == null) {
            return null;
        }
        if (hypothesis.getCandidateCommitSha() != null) {
            return hypothesis.getCandidateCommitSha();
        }
        if (hypothesis.getCandidateRootCause() != null && hypothesis.getCandidateRootCause().getEntityId() != null) {
            return hypothesis.getCandidateRootCause().getEntityId();
        }
        if (hypothesis.getSupportingEvidenceIds() != null) {
            for (String evdId : hypothesis.getSupportingEvidenceIds()) {
                if (evdId.startsWith("EVD-GIT-")) {
                    return evdId.substring("EVD-GIT-".length());
                }
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([0-9a-f]{7,40})\\b").matcher(hypothesis.getSummary());
        if (m.find()) {
            return m.group(1);
        }
        return null;
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

    private boolean isWorkingTreeAnomaly(Hypothesis hypothesis) {
        if (hypothesis == null) return false;
        if (hypothesis.getMatchedRules() != null && hypothesis.getMatchedRules().contains("RULE_WT_MISSING_TRACKED_FILES")) {
            return true;
        }
        return hypothesis.getSummary() != null && hypothesis.getSummary().contains("Working tree anomaly");
    }

    private AgentInvestigationStatus determineFinalStatus(InvestigationResult result,
                                                          Hypothesis topHypothesis,
                                                          ConfidenceBreakdown topBreakdown) {
        if (topHypothesis == null || !result.evidenceSufficient()) {
            return AgentInvestigationStatus.INSUFFICIENT_EVIDENCE;
        }

        if (isWorkingTreeAnomaly(topHypothesis)) {
            return AgentInvestigationStatus.CONFIRMED;
        }

        double conf = topHypothesis.getConfidenceScore();
        boolean hasPenalty = topBreakdown != null && topBreakdown.contradictionPenalty() > 0;

        if (conf < 0.40 || hasPenalty) {
            return AgentInvestigationStatus.INSUFFICIENT_EVIDENCE;
        }

        // A confirmed verdict requires explicit evidence satisfying grounding and validation rules.
        // A high heuristic score alone must NOT establish confirmation.
        // A configuration change alone without independent deployment verification or corroborating repeated failure logs is PROBABLE.
        boolean isExplicitlyConfirmed = topHypothesis.getStatus() == com.traceback.core.domain.HypothesisStatus.CONFIRMED;
        boolean hasDeploymentProof = hasDeploymentEvidence(result, topHypothesis);
        boolean hasCorroboratingLogBurst = topBreakdown != null && topBreakdown.agreementPoints() >= 15.0 && result.errorsDetected() > 1;
        boolean hasDirectExecutionOverlap = hasDirectCrashFrameMatch(topHypothesis);

        if (conf >= 0.75 && (isExplicitlyConfirmed || hasDeploymentProof || hasDirectExecutionOverlap || hasCorroboratingLogBurst)) {
            return AgentInvestigationStatus.CONFIRMED;
        } else {
            return AgentInvestigationStatus.PROBABLE;
        }
    }

    private boolean hasDeploymentEvidence(InvestigationResult result, Hypothesis topHypothesis) {
        if (result.timeline() != null) {
            boolean hasDeployEvent = result.timeline().stream()
                    .anyMatch(e -> "DEPLOYMENT".equalsIgnoreCase(e.eventType()) || "DEPLOYMENT".equalsIgnoreCase(e.source()));
            if (hasDeployEvent) return true;
        }
        if (result.evidenceItems() != null) {
            return result.evidenceItems().stream()
                    .anyMatch(item -> item.summary() != null && item.summary().toLowerCase().contains("deploy"));
        }
        return false;
    }

    private boolean hasDirectCrashFrameMatch(Hypothesis topHypothesis) {
        if (topHypothesis == null || topHypothesis.getMatchedRules() == null) {
            return false;
        }
        return topHypothesis.getMatchedRules().contains("RULE_B_STACK_TRACE_OVERLAP");
    }

    private String formatDefaultSummary(InvestigationResult result, Hypothesis topHypothesis, AgentInvestigationStatus verdict) {
        if (isWorkingTreeAnomaly(topHypothesis)) {
            return topHypothesis.getSummary();
        }

        String excDisplay = result.primaryException() != null ? result.primaryException().formatTypeChain() : "Unknown error";
        String sha = getCommitSha(topHypothesis);

        if (verdict == AgentInvestigationStatus.INSUFFICIENT_EVIDENCE || topHypothesis == null || sha == null) {
            return String.format("Investigation into %s in %s service is inconclusive due to insufficient evidence. Available telemetry is inadequate to establish a verified causal link.",
                    excDisplay, result.serviceName());
        }

        if (verdict == AgentInvestigationStatus.CONFIRMED) {
            return String.format("Investigation confirmed commit %s as the root cause of %s in %s service (heuristic confidence: %d%%).",
                    sha, excDisplay, result.serviceName(), (int) (topHypothesis.getConfidenceScore() * 100));
        }

        return String.format("Investigation identified commit %s as a probable contributing change for %s in %s service (heuristic confidence: %d%%). Available evidence supports investigating this change, but does not independently establish active deployment during the incident.",
                sha, excDisplay, result.serviceName(), (int) (topHypothesis.getConfidenceScore() * 100));
    }

    private String formatFallbackReasoning(InvestigationResult result, Hypothesis topHypothesis, ConfidenceBreakdown topBreakdown, AgentInvestigationStatus verdict) {
        if (isWorkingTreeAnomaly(topHypothesis)) {
            return topHypothesis.getSummary() + "\n\nForensic Analysis:\n"
                    + "- The repository contains tracked files that physically do not exist on the local filesystem.\n"
                    + "- HEAD commit tracks all missing files. No commit in repository history contains a deletion diff for these paths.\n"
                    + "- Physical absence is confirmed by filesystem inspection.\n"
                    + "- The responsible deletion process (e.g. antivirus quarantine, sync client, script, or user error) is uncorroborated without system audit logs.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Deterministic Fallback Analysis:\n");

        String excDisplay = result.primaryException() != null
                ? result.primaryException().formatChain()
                : null;

        int logErrors = result.errorsDetected();
        int commits = result.commitsAnalyzed();

        if (excDisplay != null && logErrors > 0) {
            sb.append(String.format("Evaluated %d commits against observed runtime failure telemetry (%d error log(s) and crash stack trace: %s). ",
                    commits, logErrors, excDisplay));
        } else if (excDisplay != null) {
            sb.append(String.format("Evaluated %d commits against observed runtime crash stack trace (%s). ",
                    commits, excDisplay));
        } else {
            sb.append(String.format("Evaluated %d commits against %d observed error logs. ",
                    commits, logErrors));
        }

        String sha = getCommitSha(topHypothesis);
        if (verdict == AgentInvestigationStatus.INSUFFICIENT_EVIDENCE || topHypothesis == null || sha == null) {
            sb.append("No candidate commits exhibited sufficient temporal proximity or code/config overlap to establish a confident hypothesis. Honest abstention enforced.");
            return sb.toString();
        }

        sb.append(String.format("Commit %s emerged as the leading candidate with heuristic confidence score %d%%. ",
                sha, (int) (topHypothesis.getConfidenceScore() * 100)));

        if (topBreakdown != null) {
            sb.append(String.format("Heuristic scoring breakdown: Temporal points: %.1f, Overlap points: %.1f, Agreement points: %.1f, Contradiction penalties: %.1f (raw score: %.1f pts). ",
                    topBreakdown.temporalPoints(), topBreakdown.overlapPoints(), topBreakdown.agreementPoints(),
                    topBreakdown.contradictionPenalty(), topBreakdown.rawScore()));
            if (!topBreakdown.penaltyReasons().isEmpty()) {
                sb.append("Identified penalties: ").append(String.join("; ", topBreakdown.penaltyReasons())).append(". ");
            }
        }

        if (verdict == AgentInvestigationStatus.PROBABLE) {
            sb.append("Final verdict is PROBABLE: While structural and temporal correlation are strong, independent verification that this configuration was deployed and active during the failure window is required.");
        } else if (verdict == AgentInvestigationStatus.CONFIRMED) {
            sb.append("Final verdict is CONFIRMED: Strong deterministic evidence directly links the suspect change to the observed crash with verified deployment/execution proof.");
        }

        return sb.toString();
    }

    private List<String> generateDefaultVerificationSteps(InvestigationResult result, Hypothesis topHypothesis) {
        if (isWorkingTreeAnomaly(topHypothesis)) {
            return List.of(
                    "Verify whether files were removed by an external process, script, or environment synchronization.",
                    "Restore missing working-tree files from HEAD using 'git checkout HEAD -- <paths>' if disappearance was unintended.",
                    "Review operating system file-system audit logs for process deletion events."
            );
        }

        List<String> steps = new ArrayList<>();
        String sha = getCommitSha(topHypothesis);
        if (topHypothesis != null && sha != null) {
            steps.add(String.format("Inspect diff of commit %s and review modified classes/configuration.", sha));
            steps.add("Verify active deployment status: Confirm whether this commit was built, deployed, and active in the target environment at the incident time.");
            steps.add("Check runtime connection pool / resource saturation metrics around the failure window.");
        }
        if (result.primaryException() != null) {
            steps.add(String.format("Reproduce '%s' in a staging environment with the suspect commit reverted.",
                    result.primaryException().formatTypeChain()));
        }
        steps.add("Verify health check and active database connection counts during peak load.");
        return steps;
    }

    private List<String> generateDefaultUncertainties(InvestigationResult result, ConfidenceBreakdown topBreakdown, AgentInvestigationStatus verdict) {
        Hypothesis top = result.hypotheses().isEmpty() ? null : result.hypotheses().getFirst();
        if (isWorkingTreeAnomaly(top)) {
            return List.of(
                    "Git evidence confirms physical absence of tracked files from the working directory, but cannot establish which process (e.g. user action, IDE, antivirus, background sync) removed them without OS filesystem audit logs.",
                    "No committed deletion exists in repository history (HEAD tracks all missing files)."
            );
        }

        List<String> uncertainties = new ArrayList<>();
        if (topBreakdown != null && !topBreakdown.penaltyReasons().isEmpty()) {
            uncertainties.addAll(topBreakdown.penaltyReasons());
        }
        if (verdict == AgentInvestigationStatus.PROBABLE) {
            uncertainties.add("Active deployment unconfirmed: Telemetry does not independently verify that the modified configuration or code was deployed and active in the runtime environment during the incident.");
            uncertainties.add("Concurrency and pool saturation metrics during the failure window should be verified via Prometheus/Grafana or application metrics.");
        } else if (verdict == AgentInvestigationStatus.INSUFFICIENT_EVIDENCE) {
            uncertainties.add("Telemetry sample size is insufficient to correlate root cause; additional logs, metrics, or traces required.");
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
