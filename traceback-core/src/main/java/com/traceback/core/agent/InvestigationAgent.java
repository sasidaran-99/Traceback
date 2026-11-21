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
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.logging.InvestigationLoggingContext;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceFrame;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.ClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    @Autowired(required = false)
    private com.traceback.core.persistence.InvestigationPersistenceCoordinator persistenceCoordinator;

    @Autowired(required = false)
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired(required = false)
    private InvestigationAgentStepRepository agentStepRepository;

    @Autowired(required = false)
    private InvestigationClaimRepository claimRepository;

    @Autowired(required = false)
    private InvestigationRepository investigationRepository;

    @Autowired(required = false)
    private InvestigationPersistenceMapper persistenceMapper = new InvestigationPersistenceMapper();

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
        try (var ignored = InvestigationLoggingContext.withInvestigationId(invId)) {
        List<AgentInvestigationStep> steps = new ArrayList<>();
        int stepNum = 1;

        // Ensure all evidence items from deterministic result are available in the active registry
        if (result.evidenceItems() != null) {
            for (EvidenceItem item : result.evidenceItems()) {
                evidenceRegistry.registerItem(item, invId);
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
        String tavilyStatus;
        if (tavilyService.isAvailable()) {
            try {
                findings.addAll(conductResearch(result));
                tavilyStatus = "SUCCESS";
                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "External Technical Research",
                        String.format("Retrieved %d technical research findings from Tavily.", findings.size()),
                        Map.of("findingCount", findings.size(), "queriesExecuted", findings.isEmpty() ? 0 : 1, "status", tavilyStatus)
                ));
            } catch (Exception e) {
                log.warn("Tavily research encountered error: {}", e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
                if (msg.contains("timeout") || msg.contains("timed out")) {
                    tavilyStatus = "TIMEOUT";
                } else {
                    tavilyStatus = "REQUEST_FAILURE";
                }
                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "External Technical Research",
                        "Tavily research failed: " + e.getMessage() + ". Continuing with deterministic context.",
                        Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error", "status", tavilyStatus)
                ));
            }
        } else {
            tavilyStatus = "SKIPPED_UNCONFIGURED";
            steps.add(AgentInvestigationStep.of(
                    stepNum++,
                    "External Technical Research",
                    "Tavily research service not configured (API key missing). Skipped external search.",
                    Map.of("status", tavilyStatus)
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
        String bifrostStatus;

        if (bifrostClient.isAvailable()) {
            try {
                BifrostRequest aiRequest = buildBifrostRequest(result, findings, topHypothesis, topBreakdown);
                BifrostResponse aiResponse = bifrostClient.chatCompletion(aiRequest);
                String completionText = aiResponse.firstContent();
                bifrostStatus = "SUCCESS";

                aiReasoning = completionText;
                summary = extractSection(completionText, "SUMMARY",
                        formatDefaultSummary(result, topHypothesis, finalStatus));

                // Guard against AI hallucinated verdict overrides:
                if (isWorkingTreeAnomaly(topHypothesis)) {
                    summary = topHypothesis.getSummary();
                    primaryRootCause = "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.";
                } else if (finalStatus == AgentInvestigationStatus.INSUFFICIENT_EVIDENCE) {
                    summary = formatDefaultSummary(result, topHypothesis, finalStatus);
                    primaryRootCause = result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS
                            ? "None: Working tree is clean and synchronized with HEAD."
                            : "None: Available telemetry is insufficient to establish a primary cause.";
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

                Map<String, Object> findingsMap = new LinkedHashMap<>();
                findingsMap.put("model", bifrostClient.getConfig().model());
                findingsMap.put("status", bifrostStatus);
                if (aiResponse.id() != null && !aiResponse.id().isBlank()) {
                    findingsMap.put("bifrostResponseId", aiResponse.id());
                }

                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "Bifrost AI Gateway Reasoning",
                        String.format("Synthesized root-cause reasoning via model '%s' (tokens: %d).",
                                aiResponse.model() != null ? aiResponse.model() : bifrostClient.getConfig().model(),
                                aiResponse.usage() != null ? aiResponse.usage().total_tokens() : 0),
                        findingsMap
                ));

            } catch (Exception e) {
                log.warn("Bifrost call failed, falling back to deterministic reasoning: {}", e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
                if (msg.contains("timeout") || msg.contains("timed out")) {
                    bifrostStatus = "TIMEOUT";
                } else if (msg.contains("401") || msg.contains("auth") || msg.contains("unauthorized") || msg.contains("api key")) {
                    bifrostStatus = "AUTH_FAILURE";
                } else {
                    bifrostStatus = "REQUEST_FAILURE";
                }
                steps.add(AgentInvestigationStep.of(
                        stepNum++,
                        "Bifrost AI Gateway Reasoning",
                        "Bifrost gateway error: " + e.getMessage() + ". Executed deterministic fallback.",
                        Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error", "status", bifrostStatus)
                ));
                summary = formatDefaultSummary(result, topHypothesis, finalStatus);
                primaryRootCause = isWorkingTreeAnomaly(topHypothesis)
                        ? "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established."
                        : (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS
                                ? "None: Working tree is clean and synchronized with HEAD."
                                : (topHypothesis != null ? topHypothesis.getSummary() : "None: Telemetry is insufficient to establish a primary cause."));
                aiReasoning = formatFallbackReasoning(result, topHypothesis, topBreakdown, finalStatus);
                verificationSteps = generateDefaultVerificationSteps(result, topHypothesis);
                uncertainties = generateDefaultUncertainties(result, topBreakdown, finalStatus);
            }
        } else {
            bifrostStatus = "UNCONFIGURED";
            steps.add(AgentInvestigationStep.of(
                    stepNum++,
                    "Bifrost AI Gateway Reasoning",
                    "Bifrost client not configured (API key missing). Deterministic fallback reasoning generated.",
                    Map.of("status", bifrostStatus)
            ));
            summary = formatDefaultSummary(result, topHypothesis, finalStatus);
            primaryRootCause = isWorkingTreeAnomaly(topHypothesis)
                    ? "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established."
                    : (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS
                            ? "None: Working tree is clean and synchronized with HEAD."
                            : (topHypothesis != null ? topHypothesis.getSummary() : "None: Telemetry is insufficient to establish a primary cause."));
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
        long unsuppCount = validatedClaims.stream().filter(c -> c.status() == ClaimValidationStatus.UNSUPPORTED || c.status() == ClaimValidationStatus.CONTRADICTED || c.status() == ClaimValidationStatus.VALIDATION_ERROR).count();

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

        List<String> supportingEvidenceIds;
        if (isWorkingTreeAnomaly(topHypothesis)) {
            supportingEvidenceIds = topHypothesis.getSupportingEvidenceIds() != null
                    ? topHypothesis.getSupportingEvidenceIds()
                    : Collections.emptyList();
        } else if (topHypothesis != null && topCommitSha != null) {
            supportingEvidenceIds = result.evidenceByCommitSha().getOrDefault(topCommitSha, Collections.emptyList());
        } else {
            supportingEvidenceIds = Collections.emptyList();
        }

        List<String> contradictoryEvidenceIds;
        if (isWorkingTreeAnomaly(topHypothesis)) {
            contradictoryEvidenceIds = topHypothesis.getContradictingEvidenceIds() != null
                    ? topHypothesis.getContradictingEvidenceIds()
                    : Collections.emptyList();
        } else if (topHypothesis != null && topCommitSha != null && result.counterEvidenceByCommitSha().containsKey(topCommitSha)) {
            contradictoryEvidenceIds = List.of(result.counterEvidenceByCommitSha().get(topCommitSha));
        } else {
            contradictoryEvidenceIds = Collections.emptyList();
        }

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
                Instant.now(),
                bifrostStatus,
                tavilyStatus
        );

        // Durable PostgreSQL persistence
        boolean persisted = persistReport(report);
        if (persisted) {
            report = report.withPersistenceStatus(true, "DURABLE");
        } else {
            report = report.withPersistenceStatus(false, "PERSISTENCE_FAILED");
        }

        reportStore.put(invId, report);

        log.info("Agent report {} generated for investigation {} with status {} (persistedDurable={})",
                report.reportId(), invId, finalStatus, report.persistedDurable());
        return report;
        }
    }

    private boolean persistReport(InvestigationAgentReport report) {
        if (persistenceCoordinator == null) {
            log.warn("InvestigationPersistenceCoordinator is unavailable; skipping durable persistence to prevent uncoordinated/non-transactional writes for report {}", report.reportId());
            return false;
        }
        try {
            return persistenceCoordinator.persistReportCoordinated(report);
        } catch (Exception e) {
            log.error("Failed to persist agent report {} coordinated: {}", report.reportId(), SecretRedactor.redactText(e.getMessage()));
            return false;
        }
    }

    /**
     * Persists agent audit steps transactionally, enforcing deterministic identity and avoiding duplication.
     */
    @Transactional
    public void persistAgentSteps(UUID investigationId, List<AgentInvestigationStep> steps) {
        if (agentStepRepository == null || steps == null) {
            return;
        }
        for (AgentInvestigationStep step : steps) {
            Optional<AgentStepEntity> existing = agentStepRepository.findByInvestigationIdAndStepNumber(investigationId, step.stepNumber());
            AgentStepEntity entity = persistenceMapper.toEntity(
                    step,
                    investigationId,
                    existing.map(AgentStepEntity::getId).orElse(null)
            );
            agentStepRepository.save(entity);
        }

        // Clean up any trailing steps if a forced rerun produced fewer steps
        List<AgentStepEntity> existingSteps = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(investigationId);
        for (AgentStepEntity se : existingSteps) {
            if (se.getStepNumber() > steps.size()) {
                agentStepRepository.delete(se);
            }
        }
    }

    public void cacheReport(InvestigationAgentReport report) {
        if (report != null && report.investigationId() != null) {
            reportStore.put(report.investigationId(), report);
        }
    }

    public List<InvestigationAgentReport> getAllReports() {
        if (agentReportRepository != null) {
            List<AgentReportEntity> allEntities = agentReportRepository.findAll();
            List<InvestigationAgentReport> reports = new ArrayList<>();
            for (AgentReportEntity entity : allEntities) {
                InvestigationAgentReport cached = reportStore.get(entity.getInvestigationId());
                if (cached != null && cached.reportId().equals(entity.getReportId())) {
                    reports.add(cached);
                } else {
                    List<AgentInvestigationStep> steps = getInvestigationSteps(entity.getInvestigationId());
                    InvestigationAgentReport reconstructed = persistenceMapper.toDomain(entity, steps)
                            .withPersistenceStatus(true, "DURABLE");
                    reports.add(reconstructed);
                }
            }
            return reports;
        }
        return List.copyOf(reportStore.values());
    }

    public Optional<InvestigationAgentReport> getReport(UUID investigationId) {
        InvestigationAgentReport cached = reportStore.get(investigationId);
        if (cached != null) {
            return Optional.of(cached);
        }

        if (agentReportRepository != null) {
            Optional<AgentReportEntity> entityOpt = Optional.empty();
            if (investigationRepository != null) {
                Optional<com.traceback.core.domain.InvestigationEntity> invOpt = investigationRepository.findById(investigationId);
                if (invOpt.isPresent() && invOpt.get().getReportId() != null && !invOpt.get().getReportId().isBlank()) {
                    entityOpt = agentReportRepository.findByReportId(invOpt.get().getReportId());
                }
            }
            if (entityOpt.isEmpty()) {
                entityOpt = agentReportRepository.findLatestByInvestigationId(investigationId);
            }

            if (entityOpt.isPresent()) {
                List<AgentInvestigationStep> steps = getInvestigationSteps(investigationId);
                InvestigationAgentReport reconstructed = persistenceMapper.toDomain(entityOpt.get(), steps)
                        .withPersistenceStatus(true, "DURABLE");
                reportStore.put(investigationId, reconstructed);
                return Optional.of(reconstructed);
            }
        }

        return Optional.empty();
    }

    public List<InvestigationAgentReport> getReportHistory(UUID investigationId) {
        if (agentReportRepository != null) {
            List<AgentReportEntity> entities = agentReportRepository.findByInvestigationIdOrderByCreatedAtDesc(investigationId);
            List<AgentInvestigationStep> steps = getInvestigationSteps(investigationId);
            return entities.stream().map(e -> persistenceMapper.toDomain(e, steps).withPersistenceStatus(true, "DURABLE")).toList();
        }
        InvestigationAgentReport cached = reportStore.get(investigationId);
        return cached != null ? List.of(cached) : Collections.emptyList();
    }

    public List<ExternalResearchFinding> getResearchFindings(UUID investigationId) {
        return getReport(investigationId).map(InvestigationAgentReport::externalResearchFindings).orElse(Collections.emptyList());
    }

    public List<AgentInvestigationStep> getInvestigationSteps(UUID investigationId) {
        InvestigationAgentReport cached = reportStore.get(investigationId);
        if (cached != null && cached.investigationSteps() != null && !cached.investigationSteps().isEmpty()) {
            return cached.investigationSteps();
        }

        if (agentStepRepository != null) {
            List<AgentStepEntity> entities = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(investigationId);
            if (!entities.isEmpty()) {
                return entities.stream().map(persistenceMapper::toDomain).toList();
            }
        }

        return Collections.emptyList();
    }

    public void setPersistenceCoordinator(com.traceback.core.persistence.InvestigationPersistenceCoordinator persistenceCoordinator) {
        this.persistenceCoordinator = persistenceCoordinator;
    }

    public com.traceback.core.persistence.InvestigationPersistenceCoordinator getPersistenceCoordinator() {
        return persistenceCoordinator;
    }

    public void setAgentReportRepository(InvestigationAgentReportRepository agentReportRepository) {
        this.agentReportRepository = agentReportRepository;
    }

    public void setAgentStepRepository(InvestigationAgentStepRepository agentStepRepository) {
        this.agentStepRepository = agentStepRepository;
    }

    public void setClaimRepository(InvestigationClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    public void setInvestigationRepository(InvestigationRepository investigationRepository) {
        this.investigationRepository = investigationRepository;
    }

    @Autowired(required = false)
    public void setPersistenceMapper(InvestigationPersistenceMapper persistenceMapper) {
        if (persistenceMapper != null) {
            this.persistenceMapper = persistenceMapper;
        }
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
            String serviceContext = (result.serviceName() != null && !result.serviceName().isBlank())
                    ? result.serviceName() + " failure"
                    : "software failure";
            findings.addAll(tavilyService.research(q1, serviceContext));
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

        String sanitizedUserPrompt = SecretRedactor.getInstance().redact(sb.toString());
        List<BifrostMessage> messages = List.of(
                BifrostMessage.system(systemPrompt),
                BifrostMessage.user(sanitizedUserPrompt)
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

        if (conf < 0.35 || hasPenalty) {
            return AgentInvestigationStatus.INSUFFICIENT_EVIDENCE;
        }

        // A confirmed verdict requires strong evidence (score >= 0.80 with zero contradictions,
        // or explicit confirmation, deployment proof, or direct stack frame match with confidence >= 0.50).
        // Modest scores (e.g. Scenario A at 71%, Scenario E at 76%) without deployment proof remain PROBABLE,
        // unless direct execution overlap is confirmed with confidence >= 0.50.
        boolean isExplicitlyConfirmed = topHypothesis.getStatus() == com.traceback.core.domain.HypothesisStatus.CONFIRMED;
        boolean hasDeploymentProof = hasDeploymentEvidence(result, topHypothesis);
        boolean hasDirectExecutionOverlap = hasDirectCrashFrameMatch(topHypothesis) && conf >= 0.50;
        boolean isStrongUncontradicted = conf >= 0.80 && (topBreakdown == null || topBreakdown.contradictionPenalty() == 0);

        if (isExplicitlyConfirmed || hasDeploymentProof || hasDirectExecutionOverlap || isStrongUncontradicted) {
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
            String genSummary = topHypothesis.getSummary();
            if (genSummary != null && !genSummary.isBlank()) {
                return genSummary + ". Tracked files are missing from disk without any committed deletion in Git history. Responsible external process remains unknown.";
            }
            return "Working tree filesystem anomaly confirmed: Tracked files are missing from disk without any committed deletion in Git history. Responsible external process remains unknown.";
        }

        String excDisplay = result.primaryException() != null ? result.primaryException().formatTypeChain() : "Unknown error";
        String sha = getCommitSha(topHypothesis);

        if (verdict == AgentInvestigationStatus.INSUFFICIENT_EVIDENCE || topHypothesis == null || sha == null) {
            if (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS) {
                return String.format("Working-tree forensic analysis for %s service found no uncommitted discrepancies or missing tracked files in the repository.",
                        result.serviceName());
            }
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

        if (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS) {
            return "Working-tree forensic inspection completed. The repository working tree is clean and matches HEAD with no uncommitted modifications, staged changes, or missing tracked files.";
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
        } else if (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS) {
            sb.append(String.format("Evaluated %d commits and working-tree state for %s service. ", commits, result.serviceName()));
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
                    "[Suggested] Review operating system filesystem audit logs (e.g. Auditd or Event Viewer) for file deletion events.",
                    "[Suggested] Review background synchronization tools, IDE plugins, and build scripts that touch the working directory.",
                    "[Suggested] Verify local repository working tree status using 'git status --porcelain'.",
                    "[Pending verification] [POTENTIALLY DESTRUCTIVE - REQUIRES USER CONFIRMATION] Restore missing files from HEAD using 'git checkout HEAD -- <paths>' only after confirming disappearance was unintended."
            );
        }

        if (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS) {
            return List.of(
                    "[Suggested] Verify that the local repository branch is up-to-date with remote HEAD.",
                    "[Suggested] No further working-tree remediation required; working tree is clean."
            );
        }

        List<String> steps = new ArrayList<>();
        String sha = getCommitSha(topHypothesis);
        String errorText = "";
        if (result.primaryException() != null) {
            errorText = result.primaryException().formatTypeChain() + " " + result.primaryException().message();
        } else if (result.evidenceItems() != null) {
            StringBuilder sb = new StringBuilder();
            for (EvidenceItem item : result.evidenceItems()) {
                if (item.evidenceType() == com.traceback.core.evidence.EvidenceType.ERROR_SIGNATURE
                        || item.evidenceType() == com.traceback.core.evidence.EvidenceType.LOG_ERROR
                        || item.evidenceType() == com.traceback.core.evidence.EvidenceType.STACK_TRACE) {
                    sb.append(item.summary()).append(" ").append(item.originalReference()).append(" ");
                }
            }
            errorText = sb.toString();
        }
        String errorLower = errorText.toLowerCase(Locale.ROOT);

        // Context-aware: JavaScript / Node TypeError
        if (errorLower.contains("typeerror") || (topHypothesis != null && topHypothesis.getSummary() != null && topHypothesis.getSummary().contains(".js"))) {
            steps.add("[Suggested] Inspect undefined object property access and verify data source schema in failing stack frame.");
            steps.add("[Suggested] Validate API response shape and ensure upstream service provides required nested properties.");
            steps.add("[Suggested] Implement defensive optional chaining (e.g. obj?.details) and explicit null checks.");
            steps.add("[Pending verification] Reproduce failing request in staging environment with identical input payload.");
            if (sha != null) {
                steps.add(String.format("[Suggested] Compare suspect commit %s against previous working revision in source control.", sha));
            }
            return steps;
        }

        // Context-aware: External network / socket / gateway timeout
        if (errorLower.contains("sockettimeoutexception") || errorLower.contains("connectexception") || errorLower.contains("connect timed out") || errorLower.contains("gateway")) {
            steps.add("[Suggested] Verify DNS resolution and outbound network routing for target gateway endpoint.");
            steps.add("[Suggested] Test outbound network connectivity using ping or curl from the affected container/host.");
            steps.add("[Suggested] Inspect security group egress rules, firewall tables, and corporate proxy configurations.");
            steps.add("[Suggested] Check external service status dashboard for third-party outage or degradation.");
            steps.add("[Suggested] Review socket connect timeout and read timeout parameters in client configuration.");
            return steps;
        }

        // Context-aware: Database connection pool exhaustion / SQL timeout
        if (errorLower.contains("connection slots") || errorLower.contains("sqltimeoutexception") || errorLower.contains("hikari") || errorLower.contains("connection timeout") || errorLower.contains("psqlexception")) {
            steps.add("[Suggested] Inspect connection pool configuration (maximum-pool-size, connection-timeout) against database server max_connections.");
            steps.add("[Suggested] Inspect active and idle database connections on PostgreSQL server (e.g. pg_stat_activity).");
            steps.add("[Suggested] Audit repository database transaction scopes for unclosed connections or long-running queries.");
            if (sha != null) {
                steps.add(String.format("[Pending verification] Confirm active deployment timing: verify CI/CD release logs for commit %s.", sha));
            }
            steps.add("[Pending verification] Reproduce under controlled synthetic concurrency in staging with suspect pool parameters.");
            return steps;
        }

        // General fallback
        if (sha != null) {
            steps.add(String.format("[Suggested] Inspect diff of commit %s and review modified classes/configuration.", sha));
            steps.add("[Pending verification] Verify active deployment status: confirm whether this commit was built, deployed, and active in the target environment at the incident time.");
        }
        if (result.primaryException() != null) {
            steps.add(String.format("[Pending verification] Reproduce '%s' in a staging environment with the suspect commit reverted.",
                    result.primaryException().formatTypeChain()));
        }
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

        if (result.investigationMode() == InvestigationMode.WORKING_TREE_FORENSICS) {
            return List.of(
                    "Repository working directory is synchronized with HEAD. No uncommitted modifications or missing files detected."
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
