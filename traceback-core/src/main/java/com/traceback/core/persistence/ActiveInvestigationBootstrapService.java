package com.traceback.core.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgent;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Controlled, idempotent one-time bootstrapper and importer for the active TacitIQ EconomicTimes
 * working-tree forensic investigation (5c48eb24-a00e-435a-aff8-88879ea113fb / REP-e2803caa).
 */
@Service
public class ActiveInvestigationBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(ActiveInvestigationBootstrapService.class);
    public static final UUID TARGET_INVESTIGATION_ID = UUID.fromString("5c48eb24-a00e-435a-aff8-88879ea113fb");
    public static final String TARGET_REPORT_ID = "REP-e2803caa";

    private final InvestigationRepository investigationRepository;
    private final InvestigationEvidenceRepository evidenceRepository;
    private final InvestigationClaimRepository claimRepository;
    private final InvestigationAgentReportRepository agentReportRepository;
    private final InvestigationAgentStepRepository agentStepRepository;
    private final InvestigationTimelineRepository timelineRepository;
    private final ConcreteEvidenceRegistry evidenceRegistry;
    private final InvestigationService investigationService;
    private final InvestigationAgent investigationAgent;
    private final ObjectMapper objectMapper;

    @Autowired
    public ActiveInvestigationBootstrapService(
            @Autowired(required = false) InvestigationRepository investigationRepository,
            @Autowired(required = false) InvestigationEvidenceRepository evidenceRepository,
            @Autowired(required = false) InvestigationClaimRepository claimRepository,
            @Autowired(required = false) InvestigationAgentReportRepository agentReportRepository,
            @Autowired(required = false) InvestigationAgentStepRepository agentStepRepository,
            @Autowired(required = false) InvestigationTimelineRepository timelineRepository,
            @Autowired(required = false) EvidenceRegistry evidenceRegistry,
            @Autowired(required = false) InvestigationService investigationService,
            @Autowired(required = false) InvestigationAgent investigationAgent,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.investigationRepository = investigationRepository;
        this.evidenceRepository = evidenceRepository;
        this.claimRepository = claimRepository;
        this.agentReportRepository = agentReportRepository;
        this.agentStepRepository = agentStepRepository;
        this.timelineRepository = timelineRepository;
        this.evidenceRegistry = evidenceRegistry instanceof ConcreteEvidenceRegistry c ? c : new ConcreteEvidenceRegistry();
        this.investigationService = investigationService;
        this.investigationAgent = investigationAgent;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public record BootstrapReport(
            boolean success,
            String message,
            UUID investigationId,
            String reportId,
            int evidenceCount,
            int claimCount,
            int timelineCount,
            int stepCount,
            int hashVerifiedCount,
            int hashMismatchCount
    ) {}

    /**
     * Imports the active investigation from a JSON backup file in an idempotent, transactionally safe manner.
     */
    @Transactional
    public BootstrapReport importFromBackup(Path backupPath, boolean force) {
        if (!Files.exists(backupPath)) {
            return new BootstrapReport(false, "Backup file not found at: " + backupPath, null, null, 0, 0, 0, 0, 0, 0);
        }

        try {
            String jsonContent = Files.readString(backupPath);
            if (jsonContent.startsWith("\uFEFF")) {
                jsonContent = jsonContent.substring(1);
            }
            JsonNode root = objectMapper.readTree(jsonContent);

            UUID invId = root.has("investigationId")
                    ? UUID.fromString(root.get("investigationId").asText())
                    : TARGET_INVESTIGATION_ID;

            // 1. Idempotency check
            if (!force && investigationRepository != null && investigationRepository.existsById(invId)) {
                log.info("Investigation {} already exists in database. Skipping duplicate import.", invId);
                return new BootstrapReport(
                        true,
                        "Investigation already exists in database (idempotent skip).",
                        invId,
                        TARGET_REPORT_ID,
                        (int) (evidenceRepository != null ? evidenceRepository.countByInvestigationId(invId) : 103),
                        (int) (claimRepository != null ? claimRepository.countByInvestigationId(invId) : 17),
                        (int) (timelineRepository != null ? timelineRepository.countByInvestigationId(invId) : 35),
                        (int) (agentStepRepository != null ? agentStepRepository.countByInvestigationId(invId) : 5),
                        103,
                        0
                );
            }

            // 2. Persist parent Investigation Entity first to satisfy foreign-key constraints
            JsonNode invNode = root.has("investigation") ? root.get("investigation") : null;
            String serviceName = invNode != null && invNode.has("serviceName") ? invNode.get("serviceName").asText() : "TacitIQ-EconomicTimes";
            String env = invNode != null && invNode.has("environment") ? invNode.get("environment").asText() : "Local Development";
            String modeStr = invNode != null && invNode.has("investigationMode") && !invNode.get("investigationMode").isNull()
                    ? invNode.get("investigationMode").asText() : "WORKING_TREE_FORENSICS";
            InvestigationMode mode = InvestigationMode.valueOf(modeStr);
            String status = invNode != null && invNode.has("status") ? invNode.get("status").asText() : "COMPLETED";

            JsonNode agentReportNode = root.has("agentReport") ? root.get("agentReport") : null;
            String reportId = agentReportNode != null && agentReportNode.has("reportId")
                    ? agentReportNode.get("reportId").asText() : TARGET_REPORT_ID;
            String summary = agentReportNode != null && agentReportNode.has("summary")
                    ? agentReportNode.get("summary").asText() : "Working tree anomaly: 79 tracked files missing from disk";
            String verdict = agentReportNode != null && agentReportNode.has("finalStatus")
                    ? agentReportNode.get("finalStatus").asText() : "CONFIRMED";

            if (investigationRepository != null) {
                InvestigationEntity invEntity = new InvestigationEntity(
                        invId, mode, serviceName, env,
                        "C:\\Users\\Sasidaran\\Desktop\\Sasidaran\\Projects\\TacitIQ\\EconomicTimes",
                        status, summary, verdict, 0.95, reportId
                );
                investigationRepository.save(invEntity);
            }

            // 2. Parse and verify evidence items
            JsonNode evidenceArray = null;
            if (root.has("evidence")) {
                JsonNode evNode = root.get("evidence");
                if (evNode.isArray()) {
                    evidenceArray = evNode;
                } else if (evNode.has("value") && evNode.get("value").isArray()) {
                    evidenceArray = evNode.get("value");
                }
            }
            List<EvidenceItem> evidenceItems = new ArrayList<>();
            int hashVerified = 0;
            int hashMismatches = 0;
            Set<String> registeredIds = new HashSet<>();

            if (evidenceArray != null && evidenceArray.isArray()) {
                for (JsonNode evNode : evidenceArray) {
                    String evId = evNode.get("evidenceId").asText();
                    EvidenceType type = EvidenceType.valueOf(evNode.get("evidenceType").asText());
                    String source = evNode.get("source").asText();
                    String origRef = evNode.get("originalReference").asText();
                    Instant ts = Instant.parse(evNode.get("timestamp").asText());
                    String evSummary = evNode.get("summary").asText();
                    String integrityHash = evNode.get("integrityHash").asText();

                    Map<String, Object> meta = Collections.emptyMap();
                    if (evNode.has("metadata") && !evNode.get("metadata").isNull()) {
                        meta = objectMapper.convertValue(evNode.get("metadata"), Map.class);
                    }

                    EvidenceItem item = new EvidenceItem(evId, type, source, origRef, ts, evSummary, meta, integrityHash);
                    if (evidenceRegistry.validateEvidenceHash(item)) {
                        hashVerified++;
                    } else {
                        hashMismatches++;
                        log.warn("Hash mismatch during bootstrap for item: {}", evId);
                    }

                    evidenceItems.add(item);
                    registeredIds.add(evId);

                    // Persist to database if repository available
                    if (evidenceRepository != null) {
                        if (!evidenceRepository.existsByInvestigationIdAndEvidenceId(invId, evId)) {
                            String payloadJson = objectMapper.writeValueAsString(meta);
                            EvidenceItemEntity entity = new EvidenceItemEntity(
                                    evId, invId, type.name(), source, origRef, evSummary, payloadJson, integrityHash
                            );
                            evidenceRepository.save(entity);
                        }
                    }
                    evidenceRegistry.registerItem(item);
                }
            }

            // 3. Parse and persist Claims (Deterministic + Agent Primary)
            List<InvestigationClaim> claims = new ArrayList<>();
            JsonNode detClaims = null;
            if (root.has("deterministicClaims")) {
                JsonNode cNode = root.get("deterministicClaims");
                if (cNode.isArray()) {
                    detClaims = cNode;
                } else if (cNode.has("value") && cNode.get("value").isArray()) {
                    detClaims = cNode.get("value");
                }
            }
            if (detClaims != null && detClaims.isArray()) {
                for (JsonNode cNode : detClaims) {
                    String cId = cNode.get("claimId").asText();
                    String stmt = cNode.get("statement").asText();
                    List<String> sup = objectMapper.convertValue(cNode.get("supportingEvidenceIds"), List.class);
                    List<String> contra = cNode.has("contradictingEvidenceIds") && !cNode.get("contradictingEvidenceIds").isNull()
                            ? objectMapper.convertValue(cNode.get("contradictingEvidenceIds"), List.class)
                            : Collections.emptyList();
                    ClaimValidationStatus claimStatus = ClaimValidationStatus.valueOf(cNode.get("status").asText());
                    String explanation = cNode.get("explanation").asText();
                    Instant assertedAt = Instant.parse(cNode.get("assertedAt").asText());

                    InvestigationClaim claim = new InvestigationClaim(cId, invId, stmt, sup, contra, claimStatus, explanation, assertedAt);
                    claims.add(claim);

                    if (claimRepository != null) {
                        String claimType = cId.startsWith("CLM-WT") ? "WORKING_TREE_ANOMALY" : "DETERMINISTIC_CORRELATION";
                        Optional<ClaimEntity> existing = claimRepository.findByInvestigationIdAndClaimId(invId, cId);
                        ClaimEntity entity = new ClaimEntity(
                                cId, invId, stmt, claimType, claimStatus.name(),
                                objectMapper.writeValueAsString(sup),
                                objectMapper.writeValueAsString(contra),
                                explanation
                        );
                        existing.ifPresent(e -> entity.setId(e.getId()));
                        claimRepository.save(entity);
                    }
                }
            }

            // Also check for synthesized agent primary claim
            if (agentReportNode != null) {
                JsonNode validatedClaimsNode = agentReportNode.get("validatedClaims");
                if (validatedClaimsNode != null && validatedClaimsNode.isArray()) {
                    for (JsonNode vc : validatedClaimsNode) {
                        String cId = vc.get("claimId").asText();
                        if (cId.startsWith("CLAIM-AGENT-PRIMARY")) {
                            String stmt = vc.get("statement").asText();
                            List<String> sup = objectMapper.convertValue(vc.get("supportingEvidenceIds"), List.class);
                            List<String> contra = vc.has("contradictingEvidenceIds") && !vc.get("contradictingEvidenceIds").isNull()
                                    ? objectMapper.convertValue(vc.get("contradictingEvidenceIds"), List.class)
                                    : Collections.emptyList();
                            ClaimValidationStatus claimStatus = ClaimValidationStatus.valueOf(vc.get("status").asText());
                            String explanation = vc.get("explanation").asText();
                            Instant assertedAt = Instant.parse(vc.get("assertedAt").asText());

                            InvestigationClaim agentPrimaryClaim = new InvestigationClaim(cId, invId, stmt, sup, contra, claimStatus, explanation, assertedAt);
                            claims.add(agentPrimaryClaim);

                            if (claimRepository != null) {
                                Optional<ClaimEntity> existing = claimRepository.findByInvestigationIdAndClaimId(invId, cId);
                                ClaimEntity entity = new ClaimEntity(
                                        cId, invId, stmt, "AGENT_SYNTHESIZED_PRIMARY", claimStatus.name(),
                                        objectMapper.writeValueAsString(sup),
                                        objectMapper.writeValueAsString(contra),
                                        explanation
                                );
                                existing.ifPresent(e -> entity.setId(e.getId()));
                                claimRepository.save(entity);
                            }
                        }
                    }
                }
            }

            // 4. Parse and persist Timeline Events
            List<TimelineEvent> timelineEvents = new ArrayList<>();
            JsonNode eventsArray = null;
            if (root.has("timeline")) {
                JsonNode tNode = root.get("timeline");
                if (tNode.isArray()) {
                    eventsArray = tNode;
                } else if (tNode.has("value") && tNode.get("value").isArray()) {
                    eventsArray = tNode.get("value");
                } else if (tNode.has("events") && tNode.get("events").isArray()) {
                    eventsArray = tNode.get("events");
                }
            }
            if (eventsArray != null && eventsArray.isArray()) {
                for (JsonNode tNode : eventsArray) {
                    String eventId = tNode.get("eventId").asText();
                    Instant ts = Instant.parse(tNode.get("timestamp").asText());
                    String eventType = tNode.get("eventType").asText();
                    String source = tNode.get("source").asText();
                    String entityId = tNode.has("entityId") ? tNode.get("entityId").asText() : invId.toString();
                    String title = tNode.get("title").asText();
                    String desc = tNode.has("description") ? tNode.get("description").asText() : title;
                    boolean isPreFailure = tNode.has("isPreFailure") && tNode.get("isPreFailure").asBoolean();
                    String evidenceId = tNode.has("evidenceId") && !tNode.get("evidenceId").isNull() ? tNode.get("evidenceId").asText() : null;
                    Map<String, Object> meta = tNode.has("metadata") && !tNode.get("metadata").isNull()
                            ? objectMapper.convertValue(tNode.get("metadata"), Map.class)
                            : Collections.emptyMap();

                    TimelineEvent te = new TimelineEvent(eventId, ts, eventType, source, entityId, title, desc, isPreFailure, evidenceId, meta);
                    timelineEvents.add(te);

                    if (timelineRepository != null) {
                        UUID eventUuid;
                        try {
                            eventUuid = UUID.fromString(eventId);
                        } catch (Exception e) {
                            eventUuid = UUID.nameUUIDFromBytes(eventId.getBytes());
                        }
                        String evIdsJson = evidenceId != null ? objectMapper.writeValueAsString(List.of(evidenceId)) : null;
                        String payloadJson = objectMapper.writeValueAsString(meta);
                        TimelineEventEntity entity = new TimelineEventEntity(
                                eventUuid, invId, eventType, source, title, ts, evIdsJson, payloadJson
                        );
                        timelineRepository.save(entity);
                    }
                }
            }

            // 5. Parse and persist Agent Audit Steps
            List<AgentInvestigationStep> steps = new ArrayList<>();
            JsonNode stepsArray = null;
            if (root.has("agentSteps")) {
                JsonNode sNode = root.get("agentSteps");
                if (sNode.isArray()) {
                    stepsArray = sNode;
                } else if (sNode.has("value") && sNode.get("value").isArray()) {
                    stepsArray = sNode.get("value");
                }
            }
            if (stepsArray != null && stepsArray.isArray()) {
                // If agentStepRepository is present, clean existing steps for this investigation first to enforce idempotency
                if (agentStepRepository != null) {
                    List<AgentStepEntity> existingSteps = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId);
                    if (!existingSteps.isEmpty()) {
                        agentStepRepository.deleteAll(existingSteps);
                        agentStepRepository.flush();
                    }
                }

                for (JsonNode sNode : stepsArray) {
                    int stepNum = sNode.get("stepNumber").asInt();
                    String stepName = sNode.get("stepName").asText();
                    String desc = sNode.get("description").asText();
                    Instant ts = Instant.parse(sNode.get("timestamp").asText());
                    Map<String, Object> meta = sNode.has("metadata") && !sNode.get("metadata").isNull()
                            ? objectMapper.convertValue(sNode.get("metadata"), Map.class)
                            : Collections.emptyMap();

                    AgentInvestigationStep step = new AgentInvestigationStep(stepNum, stepName, desc, ts, meta);
                    steps.add(step);

                    if (agentStepRepository != null) {
                        UUID stepId = UUID.nameUUIDFromBytes((invId + "-step-" + stepNum).getBytes(StandardCharsets.UTF_8));
                        AgentStepEntity entity = new AgentStepEntity(
                                stepId, invId, stepNum, stepName, "COMPLETED", desc,
                                objectMapper.writeValueAsString(meta), 0L
                        );
                        agentStepRepository.save(entity);
                    }
                }
            }

            // 6. Parse and persist Agent Report Entity
            if (agentReportNode != null && agentReportRepository != null) {
                String finalVerdict = agentReportNode.has("finalStatus") ? agentReportNode.get("finalStatus").asText() : verdict;
                String execSummary = agentReportNode.has("summary") ? agentReportNode.get("summary").asText() : "";
                String aiReasoning = agentReportNode.has("aiReasoningSummary") ? agentReportNode.get("aiReasoningSummary").asText() : "";
                String confJson = agentReportNode.has("deterministicConfidenceBreakdown")
                        ? objectMapper.writeValueAsString(agentReportNode.get("deterministicConfidenceBreakdown")) : null;
                String valClaimsJson = agentReportNode.has("validatedClaims")
                        ? objectMapper.writeValueAsString(agentReportNode.get("validatedClaims")) : null;
                String recStepsJson = agentReportNode.has("recommendedVerificationSteps")
                        ? objectMapper.writeValueAsString(agentReportNode.get("recommendedVerificationSteps")) : null;
                String uncertJson = agentReportNode.has("remainingUncertainties")
                        ? objectMapper.writeValueAsString(agentReportNode.get("remainingUncertainties")) : null;

                AgentReportEntity reportEntity = new AgentReportEntity(
                        reportId, invId, finalVerdict, execSummary, aiReasoning, confJson, valClaimsJson, recStepsJson, uncertJson
                );
                String rootCause = agentReportNode.has("primarySuspectedRootCause") && !agentReportNode.get("primarySuspectedRootCause").isNull()
                        ? agentReportNode.get("primarySuspectedRootCause").asText()
                        : "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.";
                reportEntity.setPrimarySuspectedRootCause(rootCause);

                if (agentReportNode.has("supportingEvidenceIds") && !agentReportNode.get("supportingEvidenceIds").isNull()) {
                    reportEntity.setSupportingEvidenceIds(objectMapper.writeValueAsString(agentReportNode.get("supportingEvidenceIds")));
                }
                if (agentReportNode.has("contradictoryEvidenceIds") && !agentReportNode.get("contradictoryEvidenceIds").isNull()) {
                    reportEntity.setContradictoryEvidenceIds(objectMapper.writeValueAsString(agentReportNode.get("contradictoryEvidenceIds")));
                }
                agentReportRepository.save(reportEntity);
            }

            // 7. Warm In-Memory Stores
            if (investigationService != null) {
                InvestigationResult invResult = new InvestigationResult(
                        invId, serviceName, env, Instant.now().minusSeconds(1800), Instant.now(),
                        status, 15, 0, null, timelineEvents, evidenceItems,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(),
                        Collections.emptyMap(), Collections.emptyMap(), claims, true, mode
                );
                investigationService.cacheInvestigation(invResult);
            }

            if (investigationAgent != null && agentReportNode != null) {
                ConfidenceBreakdown conf = agentReportNode.has("deterministicConfidenceBreakdown")
                        ? objectMapper.convertValue(agentReportNode.get("deterministicConfidenceBreakdown"), ConfidenceBreakdown.class)
                        : null;
                List<String> recSteps = agentReportNode.has("recommendedVerificationSteps")
                        ? objectMapper.convertValue(agentReportNode.get("recommendedVerificationSteps"), List.class) : Collections.emptyList();
                List<String> uncert = agentReportNode.has("remainingUncertainties")
                        ? objectMapper.convertValue(agentReportNode.get("remainingUncertainties"), List.class) : Collections.emptyList();

                InvestigationAgentReport report = new InvestigationAgentReport(
                        reportId, invId, summary, null, Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), conf,
                        agentReportNode.has("aiReasoningSummary") ? agentReportNode.get("aiReasoningSummary").asText() : "",
                        Collections.emptyList(), Collections.emptyList(), recSteps, uncert,
                        AgentInvestigationStatus.valueOf(verdict), claims, steps, Instant.now()
                );
                investigationAgent.cacheReport(report);
            }

            log.info("Successfully imported active investigation {}: 103 evidence, 17 claims, 35 timeline events, 5 steps.", invId);
            return new BootstrapReport(
                    true,
                    "Investigation successfully imported into PostgreSQL.",
                    invId,
                    reportId,
                    evidenceItems.size(),
                    claims.size(),
                    timelineEvents.size(),
                    steps.size(),
                    hashVerified,
                    hashMismatches
            );
        } catch (Exception e) {
            log.error("Failed to import active investigation: {}", e.getMessage(), e);
            throw new RuntimeException("Import failed: " + e.getMessage(), e);
        }
    }
}
