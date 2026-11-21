package com.traceback.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.agent.AgentInvestigationStatus;
import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.Event;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.InvestigationHypothesisEntity;
import com.traceback.core.domain.Relationship;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.investigation.InvestigationMode;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * High-fidelity mapper between Traceback domain records and JPA database entities.
 */
@Component
public class InvestigationPersistenceMapper {

    private static final Logger log = LoggerFactory.getLogger(InvestigationPersistenceMapper.class);
    private final ObjectMapper objectMapper;

    public InvestigationPersistenceMapper(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper.copy() : new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper = mapper;
    }

    public InvestigationPersistenceMapper() {
        this(null);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public List<String> deserializeEvidenceIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<InvestigationClaim> deserializeValidatedClaims(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<InvestigationClaim>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize validated claims JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------------
    // Investigation
    // ---------------------------------------------------------

    public InvestigationEntity toEntity(InvestigationResult result, String repositoryPath) {
        String verdict = null;
        Double confidence = null;
        if (result.hypotheses() != null && !result.hypotheses().isEmpty()) {
            Hypothesis top = result.hypotheses().get(0);
            verdict = top.getStatus() != null ? top.getStatus().name() : null;
            confidence = top.getConfidenceScore();
        }

        String summary = null;
        if (result.hypotheses() != null && !result.hypotheses().isEmpty()) {
            summary = result.hypotheses().get(0).getSummary();
        }

        return new InvestigationEntity(
                result.investigationId(),
                result.investigationMode() != null ? result.investigationMode() : InvestigationMode.RUNTIME_FAILURE,
                result.serviceName(),
                result.environment(),
                repositoryPath,
                result.status(),
                summary,
                verdict,
                confidence,
                null // reportId set upon agent completion
        );
    }

    public InvestigationResult toDomain(
            InvestigationEntity entity,
            List<TimelineEvent> timeline,
            List<EvidenceItem> evidence,
            List<InvestigationClaim> claims
    ) {
        return toDomain(entity, timeline, evidence, claims, Collections.emptyList());
    }

    public InvestigationResult toDomain(
            InvestigationEntity entity,
            List<TimelineEvent> timeline,
            List<EvidenceItem> evidence,
            List<InvestigationClaim> claims,
            List<Hypothesis> hypotheses
    ) {
        // 1. Calculate commitsAnalyzed from persisted Git commit evidence
        int commitsAnalyzed = 0;
        if (evidence != null) {
            commitsAnalyzed = (int) evidence.stream()
                    .filter(e -> (e.evidenceType() == EvidenceType.COMMIT || e.evidenceType() == EvidenceType.COMMIT_DIFF)
                            && "GIT".equalsIgnoreCase(e.source())
                            && e.evidenceId() != null
                            && e.evidenceId().startsWith("EVD-GIT-"))
                    .map(EvidenceItem::originalReference)
                    .distinct()
                    .count();
            if (commitsAnalyzed == 0) {
                commitsAnalyzed = (int) evidence.stream()
                        .filter(e -> (e.evidenceType() == EvidenceType.COMMIT || e.evidenceType() == EvidenceType.COMMIT_DIFF)
                                && "GIT".equalsIgnoreCase(e.source()))
                        .map(EvidenceItem::evidenceId)
                        .distinct()
                        .count();
            }
        }

        // 2. Calculate errorsDetected from actual persisted runtime error evidence (never count working-tree anomalies)
        int errorsDetected = 0;
        if (evidence != null) {
            Optional<EvidenceItem> sigOpt = evidence.stream()
                    .filter(e -> e.evidenceType() == EvidenceType.ERROR_SIGNATURE)
                    .findFirst();
            if (sigOpt.isPresent() && sigOpt.get().metadata() != null && sigOpt.get().metadata().containsKey("count")) {
                Object countObj = sigOpt.get().metadata().get("count");
                if (countObj instanceof Number n) {
                    errorsDetected = n.intValue();
                }
            }
            if (errorsDetected == 0) {
                int logErrors = (int) evidence.stream()
                        .filter(e -> e.evidenceType() == EvidenceType.LOG_ERROR)
                        .count();
                if (logErrors > 0) {
                    errorsDetected = logErrors;
                } else {
                    boolean hasStackEvidence = evidence.stream()
                            .anyMatch(e -> e.evidenceType() == EvidenceType.STACK_TRACE
                                    || e.evidenceType() == EvidenceType.STACK_FRAME);
                    if (hasStackEvidence) {
                        errorsDetected = 1;
                    }
                }
            }
        }

        // 3. Restore primaryException only when the original exception can be recovered from persisted evidence metadata
        ParsedException primaryException = null;
        if (evidence != null) {
            Optional<EvidenceItem> stackOpt = evidence.stream()
                    .filter(e -> e.evidenceType() == EvidenceType.STACK_TRACE)
                    .findFirst();
            if (stackOpt.isPresent()) {
                EvidenceItem stackItem = stackOpt.get();
                String exType = null;
                if (stackItem.metadata() != null && stackItem.metadata().containsKey("type")) {
                    exType = String.valueOf(stackItem.metadata().get("type"));
                } else if (stackItem.originalReference() != null && !stackItem.originalReference().isBlank()) {
                    exType = stackItem.originalReference();
                }
                String msg = stackItem.summary();
                if (exType != null && !exType.isBlank()) {
                    primaryException = new ParsedException(exType, msg, Collections.emptyList(), null);
                }
            } else {
                Optional<EvidenceItem> sigOpt = evidence.stream()
                        .filter(e -> e.evidenceType() == EvidenceType.ERROR_SIGNATURE)
                        .findFirst();
                if (sigOpt.isPresent()) {
                    EvidenceItem sigItem = sigOpt.get();
                    String exType = sigItem.originalReference();
                    String msg = sigItem.summary();
                    if (exType != null && !exType.isBlank()) {
                        primaryException = new ParsedException(exType, msg, Collections.emptyList(), null);
                    }
                } else {
                    Optional<EvidenceItem> frameOpt = evidence.stream()
                            .filter(e -> e.evidenceType() == EvidenceType.STACK_FRAME)
                            .findFirst();
                    if (frameOpt.isPresent()) {
                        EvidenceItem frameItem = frameOpt.get();
                        String exType = null;
                        if (frameItem.metadata() != null && frameItem.metadata().containsKey("type")) {
                            exType = String.valueOf(frameItem.metadata().get("type"));
                        } else if (frameItem.originalReference() != null && !frameItem.originalReference().isBlank()) {
                            exType = frameItem.originalReference();
                        }
                        String msg = frameItem.summary();
                        if (exType != null && !exType.isBlank()) {
                            primaryException = new ParsedException(exType, msg, Collections.emptyList(), null);
                        }
                    }
                }
            }
        }

        // 4. Relationships & Hypotheses
        List<Relationship> relationships = Collections.emptyList();
        Map<String, List<String>> evidenceByCommitSha = new HashMap<>();
        Map<String, String> counterEvidenceByCommitSha = new HashMap<>();
        Map<String, ConfidenceBreakdown> confidenceBreakdownsByCommitSha = new HashMap<>();

        if (hypotheses != null) {
            for (Hypothesis h : hypotheses) {
                String commitSha = h.getCandidateCommitSha();
                if (commitSha != null && !commitSha.isBlank()) {
                    if (h.getSupportingEvidenceIds() != null && !h.getSupportingEvidenceIds().isEmpty()) {
                        evidenceByCommitSha.put(commitSha, h.getSupportingEvidenceIds());
                    }
                    if (h.getCounterEvidence() != null && !h.getCounterEvidence().isBlank()) {
                        counterEvidenceByCommitSha.put(commitSha, h.getCounterEvidence());
                    }
                    if (h.getConfidenceBreakdown() != null) {
                        confidenceBreakdownsByCommitSha.put(commitSha, h.getConfidenceBreakdown());
                    }
                } else if ("WORKING_TREE".equalsIgnoreCase(h.getSummary())
                        || (h.getMatchedRules() != null && h.getMatchedRules().contains("RULE_WT_MISSING_TRACKED_FILES"))) {
                    if (h.getSupportingEvidenceIds() != null) {
                        evidenceByCommitSha.put("WORKING_TREE", h.getSupportingEvidenceIds());
                    }
                    if (h.getCounterEvidence() != null) {
                        counterEvidenceByCommitSha.put("WORKING_TREE", h.getCounterEvidence());
                    }
                    if (h.getConfidenceBreakdown() != null) {
                        confidenceBreakdownsByCommitSha.put("WORKING_TREE", h.getConfidenceBreakdown());
                    }
                }
            }
        }

        // 5. Canonical timeline evidence resolution: ensure all timeline events resolve against registered evidence
        List<TimelineEvent> resolvedTimeline = Collections.emptyList();
        if (timeline != null && !timeline.isEmpty()) {
            Set<String> validEvidenceIds = (evidence != null && !evidence.isEmpty())
                    ? evidence.stream().map(EvidenceItem::evidenceId).collect(Collectors.toSet())
                    : Collections.emptySet();

            resolvedTimeline = timeline.stream().map(te -> {
                String eid = te.evidenceId();
                if (eid != null && !validEvidenceIds.isEmpty() && !validEvidenceIds.contains(eid)) {
                    if (eid.startsWith("EVD-CFG-")) {
                        String gitFallback = "EVD-GIT-" + eid.substring("EVD-CFG-".length());
                        if (validEvidenceIds.contains(gitFallback)) {
                            return new TimelineEvent(
                                    te.eventId(), te.timestamp(), te.eventType(), te.source(),
                                    te.entityId(), te.title(), te.description(), te.isPreFailure(),
                                    gitFallback, te.metadata()
                            );
                        }
                    } else if (eid.startsWith("EVD-STACK-") && validEvidenceIds.contains("EVD-STACK-ROOT")) {
                        return new TimelineEvent(
                                te.eventId(), te.timestamp(), te.eventType(), te.source(),
                                te.entityId(), te.title(), te.description(), te.isPreFailure(),
                                "EVD-STACK-ROOT", te.metadata()
                        );
                    } else if (eid.startsWith("EVD-LOG-") && validEvidenceIds.contains("EVD-LOG-SIG")) {
                        return new TimelineEvent(
                                te.eventId(), te.timestamp(), te.eventType(), te.source(),
                                te.entityId(), te.title(), te.description(), te.isPreFailure(),
                                "EVD-LOG-SIG", te.metadata()
                        );
                    }
                }
                return te;
            }).toList();
        }

        return new InvestigationResult(
                entity.getId(),
                entity.getServiceName(),
                entity.getEnvironment(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStatus(),
                commitsAnalyzed,
                errorsDetected,
                primaryException,
                resolvedTimeline,
                evidence != null ? evidence : Collections.emptyList(),
                relationships,
                hypotheses != null ? hypotheses : Collections.emptyList(),
                evidenceByCommitSha,
                counterEvidenceByCommitSha,
                confidenceBreakdownsByCommitSha,
                claims != null ? claims : Collections.emptyList(),
                "COMPLETED".equalsIgnoreCase(entity.getStatus()),
                entity.getMode(),
                true,
                "DURABLE"
        );
    }

    // ---------------------------------------------------------
    // Evidence
    // ---------------------------------------------------------

    public EvidenceItemEntity toEntity(EvidenceItem item, UUID investigationId) {
        String payloadJson = null;
        UUID authoritativeInvId = investigationId != null ? investigationId : item.investigationId();

        Map<String, Object> metadataToPersist = new java.util.LinkedHashMap<>();
        if (item.metadata() != null) {
            metadataToPersist.putAll(item.metadata());
        }

        // Authoritative ownership & provenance synchronization
        if (authoritativeInvId != null) {
            String targetInvStr = authoritativeInvId.toString();
            Object existingInv = metadataToPersist.get("investigationId");
            // If item already had an originatingInvestigationId, preserve it unconditionally (never overwrite historical provenance)
            if (!metadataToPersist.containsKey("originatingInvestigationId")) {
                if (item.isGlobal() || (existingInv != null && "GLOBAL".equalsIgnoreCase(existingInv.toString()))) {
                    metadataToPersist.put("originatingInvestigationId", "GLOBAL");
                } else if (existingInv != null && !existingInv.toString().equalsIgnoreCase(targetInvStr)) {
                    metadataToPersist.put("originatingInvestigationId", existingInv.toString());
                } else {
                    metadataToPersist.put("originatingInvestigationId", targetInvStr);
                }
            }
            // Database column is authoritative: serialized metadata MUST match authoritative ownership
            metadataToPersist.put("investigationId", targetInvStr);
        }

        try {
            payloadJson = objectMapper.writeValueAsString(metadataToPersist);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata for {}: {}", item.evidenceId(), e.getMessage());
        }
        return new EvidenceItemEntity(
                item.evidenceId(),
                authoritativeInvId,
                item.evidenceType().name(),
                item.source(),
                item.originalReference(),
                item.summary(),
                payloadJson,
                item.integrityHash()
        );
    }

    public EvidenceItem toDomain(EvidenceItemEntity entity) {
        EvidenceType type;
        try {
            type = EvidenceType.valueOf(entity.getEvidenceType());
        } catch (Exception e) {
            type = EvidenceType.COMMIT;
        }

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        String rawPayloadInvId = null;
        if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
            try {
                Map<String, Object> deserialized = objectMapper.readValue(entity.getPayload(), new TypeReference<Map<String, Object>>() {});
                if (deserialized != null) {
                    metadata.putAll(deserialized);
                    if (deserialized.get("investigationId") != null) {
                        rawPayloadInvId = deserialized.get("investigationId").toString();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize payload for {}: {}", entity.getEvidenceId(), e.getMessage());
            }
        }

        // Database column entity.getInvestigationId() is ALWAYS the authoritative ownership field
        if (entity.getInvestigationId() != null) {
            String dbInvStr = entity.getInvestigationId().toString();
            // If raw payload carried an older/different investigationId, preserve it as originating provenance
            if (rawPayloadInvId != null && !rawPayloadInvId.equalsIgnoreCase(dbInvStr) && !"GLOBAL".equalsIgnoreCase(rawPayloadInvId)) {
                if (!metadata.containsKey("originatingInvestigationId")) {
                    metadata.put("originatingInvestigationId", rawPayloadInvId);
                }
            } else if (!metadata.containsKey("originatingInvestigationId")) {
                metadata.put("originatingInvestigationId", dbInvStr);
            }
            metadata.put("investigationId", dbInvStr);
        }

        return new EvidenceItem(
                entity.getEvidenceId(),
                type,
                entity.getSource(),
                entity.getOriginalReference(),
                entity.getCreatedAt(),
                entity.getSummary(),
                metadata,
                entity.getIntegrityHash()
        );
    }

    // ---------------------------------------------------------
    // Claims
    // ---------------------------------------------------------

    public ClaimEntity toEntity(InvestigationClaim claim, UUID investigationId, String claimType) {
        String supJson = null;
        String contraJson = null;
        try {
            if (claim.supportingEvidenceIds() != null) {
                supJson = objectMapper.writeValueAsString(claim.supportingEvidenceIds());
            }
            if (claim.contradictingEvidenceIds() != null) {
                contraJson = objectMapper.writeValueAsString(claim.contradictingEvidenceIds());
            }
        } catch (Exception e) {
            log.warn("Failed to serialize evidence IDs for claim {}: {}", claim.claimId(), e.getMessage());
        }

        return new ClaimEntity(
                claim.claimId(),
                investigationId,
                claim.statement(),
                claimType != null ? claimType : "DETERMINISTIC_CORRELATION",
                claim.status() != null ? claim.status().name() : ClaimValidationStatus.UNSUPPORTED.name(),
                supJson,
                contraJson,
                claim.explanation()
        );
    }

    public InvestigationClaim toDomain(ClaimEntity entity) {
        List<String> supporting = Collections.emptyList();
        List<String> contradicting = Collections.emptyList();
        try {
            if (entity.getSupportingEvidenceIds() != null && !entity.getSupportingEvidenceIds().isBlank()) {
                supporting = objectMapper.readValue(entity.getSupportingEvidenceIds(), new TypeReference<List<String>>() {});
            }
            if (entity.getContradictingEvidenceIds() != null && !entity.getContradictingEvidenceIds().isBlank()) {
                contradicting = objectMapper.readValue(entity.getContradictingEvidenceIds(), new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize evidence references for claim {}: {}", entity.getClaimId(), e.getMessage());
        }

        ClaimValidationStatus status;
        try {
            status = ClaimValidationStatus.valueOf(entity.getValidationStatus());
        } catch (Exception e) {
            status = ClaimValidationStatus.UNSUPPORTED;
        }

        return new InvestigationClaim(
                entity.getClaimId(),
                entity.getInvestigationId(),
                entity.getStatement(),
                supporting,
                contradicting,
                status,
                entity.getValidationExplanation(),
                entity.getCreatedAt()
        );
    }

    // ---------------------------------------------------------
    // Agent Reports
    // ---------------------------------------------------------

    public AgentReportEntity toEntity(InvestigationAgentReport report) {
        String confJson = null;
        String claimsJson = null;
        String stepsJson = null;
        String uncertJson = null;
        String supportingJson = null;
        String contradictoryJson = null;
        try {
            if (report.deterministicConfidenceBreakdown() != null) {
                confJson = objectMapper.writeValueAsString(report.deterministicConfidenceBreakdown());
            }
            if (report.validatedClaims() != null) {
                claimsJson = objectMapper.writeValueAsString(report.validatedClaims());
            }
            if (report.recommendedVerificationSteps() != null) {
                stepsJson = objectMapper.writeValueAsString(report.recommendedVerificationSteps());
            }
            if (report.remainingUncertainties() != null) {
                uncertJson = objectMapper.writeValueAsString(report.remainingUncertainties());
            }
            if (report.supportingEvidenceIds() != null) {
                supportingJson = objectMapper.writeValueAsString(report.supportingEvidenceIds());
            }
            if (report.contradictoryEvidenceIds() != null) {
                contradictoryJson = objectMapper.writeValueAsString(report.contradictoryEvidenceIds());
            }
        } catch (Exception e) {
            log.warn("Failed to serialize agent report fields {}: {}", report.reportId(), e.getMessage());
        }

        AgentReportEntity entity = new AgentReportEntity(
                report.reportId(),
                report.investigationId(),
                report.finalStatus() != null ? report.finalStatus().name() : "INSUFFICIENT_EVIDENCE",
                report.summary(),
                report.aiReasoningSummary(),
                confJson,
                claimsJson,
                stepsJson,
                uncertJson
        );
        entity.setPrimarySuspectedRootCause(report.primarySuspectedRootCause());
        entity.setSupportingEvidenceIds(supportingJson);
        entity.setContradictoryEvidenceIds(contradictoryJson);
        return entity;
    }

    public InvestigationAgentReport toDomain(AgentReportEntity entity, List<AgentInvestigationStep> steps) {
        ConfidenceBreakdown breakdown = null;
        List<InvestigationClaim> validatedClaims = Collections.emptyList();
        List<String> recSteps = Collections.emptyList();
        List<String> uncert = Collections.emptyList();
        List<String> supportingIds = Collections.emptyList();
        List<String> contradictoryIds = Collections.emptyList();

        if (entity.getConfidenceBreakdown() != null && !entity.getConfidenceBreakdown().isBlank()) {
            try {
                breakdown = objectMapper.readValue(entity.getConfidenceBreakdown(), ConfidenceBreakdown.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize confidence breakdown for {}: {}", entity.getReportId(), e.getMessage());
            }
        }
        if (entity.getValidatedClaims() != null && !entity.getValidatedClaims().isBlank()) {
            try {
                validatedClaims = objectMapper.readValue(entity.getValidatedClaims(), new TypeReference<List<InvestigationClaim>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize validated claims for {}: {}", entity.getReportId(), e.getMessage());
            }
        }
        if (entity.getRecommendedVerificationSteps() != null && !entity.getRecommendedVerificationSteps().isBlank()) {
            try {
                recSteps = objectMapper.readValue(entity.getRecommendedVerificationSteps(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize recSteps for {}: {}", entity.getReportId(), e.getMessage());
            }
        }
        if (entity.getRemainingUncertainties() != null && !entity.getRemainingUncertainties().isBlank()) {
            try {
                uncert = objectMapper.readValue(entity.getRemainingUncertainties(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize uncert for {}: {}", entity.getReportId(), e.getMessage());
            }
        }
        boolean supportingIdsExplicitlyStored = (entity.getSupportingEvidenceIds() != null && !entity.getSupportingEvidenceIds().isBlank());
        if (supportingIdsExplicitlyStored) {
            try {
                supportingIds = objectMapper.readValue(entity.getSupportingEvidenceIds(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize supportingEvidenceIds for {}: {}", entity.getReportId(), e.getMessage());
            }
        }
        if (entity.getContradictoryEvidenceIds() != null && !entity.getContradictoryEvidenceIds().isBlank()) {
            try {
                contradictoryIds = objectMapper.readValue(entity.getContradictoryEvidenceIds(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize contradictoryEvidenceIds for {}: {}", entity.getReportId(), e.getMessage());
            }
        }

        // Backwards compatibility strictly for genuinely legacy records where the column was absent (NULL or blank),
        // and verdict is not INSUFFICIENT_EVIDENCE. Explicitly stored empty lists are never populated with candidate evidence.
        if (!supportingIdsExplicitlyStored && !"INSUFFICIENT_EVIDENCE".equalsIgnoreCase(entity.getVerdict()) && validatedClaims != null) {
            List<String> collected = new ArrayList<>();
            for (InvestigationClaim c : validatedClaims) {
                if (c.supportingEvidenceIds() != null) {
                    for (String sid : c.supportingEvidenceIds()) {
                        if (!collected.contains(sid)) collected.add(sid);
                    }
                }
            }
            if (!collected.isEmpty()) {
                supportingIds = collected;
            }
        }

        String primaryRootCause = entity.getPrimarySuspectedRootCause();
        if (primaryRootCause == null || primaryRootCause.isBlank()) {
            if (entity.getExecutiveSummary() != null && entity.getExecutiveSummary().startsWith("Working tree anomaly")) {
                primaryRootCause = "Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.";
            } else if (entity.getExecutiveSummary() != null && !entity.getExecutiveSummary().isBlank()) {
                primaryRootCause = entity.getExecutiveSummary();
            }
        }

        AgentInvestigationStatus status;
        try {
            status = AgentInvestigationStatus.valueOf(entity.getVerdict());
        } catch (Exception e) {
            status = AgentInvestigationStatus.INSUFFICIENT_EVIDENCE;
        }

        return new InvestigationAgentReport(
                entity.getReportId(),
                entity.getInvestigationId(),
                entity.getExecutiveSummary(),
                primaryRootCause,
                Collections.emptyList(), // alternativeHypotheses
                supportingIds,
                contradictoryIds,
                breakdown,
                entity.getAiReasoningSummary(),
                Collections.emptyList(), // externalResearchFindings
                Collections.emptyList(), // sourceUrls
                recSteps,
                uncert,
                status,
                validatedClaims,
                steps != null ? steps : Collections.emptyList(),
                entity.getCreatedAt(),
                "UNCONFIGURED",
                "SKIPPED_UNCONFIGURED",
                true,
                "DURABLE"
        );
    }

    // ---------------------------------------------------------
    // Agent Steps
    // ---------------------------------------------------------

    public AgentStepEntity toEntity(AgentInvestigationStep step, UUID investigationId) {
        return toEntity(step, investigationId, null);
    }

    public AgentStepEntity toEntity(AgentInvestigationStep step, UUID investigationId, UUID existingId) {
        UUID id = existingId != null
                ? existingId
                : UUID.nameUUIDFromBytes((investigationId.toString() + ":step:" + step.stepNumber()).getBytes(StandardCharsets.UTF_8));
        String findingsJson = null;
        if (step.metadata() != null) {
            try {
                findingsJson = objectMapper.writeValueAsString(step.metadata());
            } catch (Exception e) {
                log.warn("Failed to serialize findings for step {}: {}", step.stepName(), e.getMessage());
            }
        }

        return new AgentStepEntity(
                id,
                investigationId,
                step.stepNumber(),
                step.stepName(),
                "COMPLETED",
                step.description(),
                findingsJson,
                0L
        );
    }

    public AgentInvestigationStep toDomain(AgentStepEntity entity) {
        Map<String, Object> metadata = Collections.emptyMap();
        if (entity.getFindings() != null && !entity.getFindings().isBlank()) {
            try {
                metadata = objectMapper.readValue(entity.getFindings(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize findings for step {}: {}", entity.getStepName(), e.getMessage());
            }
        }

        return new AgentInvestigationStep(
                entity.getStepNumber(),
                entity.getStepName(),
                entity.getRationale(),
                entity.getCreatedAt(),
                metadata
        );
    }

    // ---------------------------------------------------------
    // Hypotheses
    // ---------------------------------------------------------

    public InvestigationHypothesisEntity toEntity(Hypothesis hypothesis, UUID investigationId) {
        String supJson = serializeJson(hypothesis.getSupportingEvidenceIds());
        String contraJson = serializeJson(hypothesis.getContradictingEvidenceIds());
        String rulesJson = serializeJson(hypothesis.getMatchedRules());
        String matchReasonsJson = serializeJson(hypothesis.getRuleMatchReasons());
        String penaltyJson = serializeJson(hypothesis.getPenaltyReasons());
        String breakdownJson = null;
        if (hypothesis.getConfidenceBreakdown() != null) {
            try {
                breakdownJson = objectMapper.writeValueAsString(hypothesis.getConfidenceBreakdown());
            } catch (Exception e) {
                log.warn("Failed to serialize confidence breakdown: {}", e.getMessage());
            }
        }

        String commitSha = hypothesis.getCandidateCommitSha();
        String statusStr = hypothesis.getStatus() != null ? hypothesis.getStatus().name() : "PROPOSED";

        return new InvestigationHypothesisEntity(
                null,
                hypothesis.getId() != null ? hypothesis.getId() : UUID.randomUUID(),
                investigationId,
                hypothesis.getRank(),
                hypothesis.getConfidenceScore(),
                statusStr,
                hypothesis.getSummary() != null ? hypothesis.getSummary() : "",
                hypothesis.getVerificationPlan(),
                hypothesis.getCounterEvidence(),
                commitSha,
                supJson,
                contraJson,
                rulesJson,
                matchReasonsJson,
                penaltyJson,
                hypothesis.getRankingExplanation(),
                breakdownJson
        );
    }

    public Hypothesis toDomain(InvestigationHypothesisEntity entity) {
        List<String> supportingEvidenceIds = deserializeStringList(entity.getSupportingEvidenceIds());
        List<String> contradictingEvidenceIds = deserializeStringList(entity.getContradictingEvidenceIds());
        List<String> matchedRules = deserializeStringList(entity.getMatchedRules());
        Map<String, String> ruleMatchReasons = deserializeStringMap(entity.getRuleMatchReasons());
        List<String> penaltyReasons = deserializeStringList(entity.getPenaltyReasons());

        ConfidenceBreakdown breakdown = null;
        if (entity.getConfidenceBreakdown() != null && !entity.getConfidenceBreakdown().isBlank()) {
            try {
                breakdown = objectMapper.readValue(entity.getConfidenceBreakdown(), ConfidenceBreakdown.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize confidence breakdown: {}", e.getMessage());
            }
        }

        HypothesisStatus status = HypothesisStatus.PROPOSED;
        if (entity.getStatus() != null) {
            try {
                status = HypothesisStatus.valueOf(entity.getStatus().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        Event candidateEvent = null;
        if (entity.getSuspectedCommitSha() != null && !entity.getSuspectedCommitSha().isBlank()) {
            candidateEvent = new Event(
                    UUID.randomUUID(),
                    com.traceback.core.domain.EventType.DEPLOYMENT,
                    com.traceback.core.domain.EventSource.GITHUB,
                    "application",
                    "production",
                    entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now(),
                    entity.getSuspectedCommitSha(),
                    "{\"commit\":\"" + entity.getSuspectedCommitSha() + "\"}"
            );
        }

        return new Hypothesis(
                entity.getHypothesisId() != null ? entity.getHypothesisId() : entity.getId(),
                null,
                candidateEvent,
                entity.getRank(),
                entity.getConfidenceScore(),
                status,
                entity.getSummary(),
                entity.getVerificationPlan(),
                entity.getCounterEvidence(),
                supportingEvidenceIds,
                contradictingEvidenceIds,
                matchedRules,
                ruleMatchReasons,
                penaltyReasons,
                entity.getRankingExplanation(),
                breakdown
        );
    }

    private String serializeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize json: {}", e.getMessage());
            return null;
        }
    }

    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, String> deserializeStringMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // ---------------------------------------------------------
    // Timeline Events
    // ---------------------------------------------------------

    public TimelineEventEntity toEntity(TimelineEvent event, UUID investigationId) {
        String payloadJson = null;
        Map<String, Object> payloadMap = new HashMap<>();
        if (event.metadata() != null) {
            payloadMap.putAll(event.metadata());
        }
        payloadMap.put("isPreFailure", event.isPreFailure());
        try {
            payloadJson = objectMapper.writeValueAsString(payloadMap);
        } catch (Exception e) {
            log.warn("Failed to serialize payload for timeline event {}: {}", event.eventId(), e.getMessage());
        }

        String evidenceIdsJson = null;
        if (event.evidenceId() != null) {
            try {
                evidenceIdsJson = objectMapper.writeValueAsString(List.of(event.evidenceId()));
            } catch (Exception ignored) {}
        }

        UUID eventUuid;
        try {
            eventUuid = UUID.fromString(event.eventId());
        } catch (Exception e) {
            eventUuid = UUID.nameUUIDFromBytes(event.eventId().getBytes());
        }

        return new TimelineEventEntity(
                eventUuid,
                investigationId,
                event.eventType(),
                event.source(),
                event.title(),
                event.timestamp(),
                evidenceIdsJson,
                payloadJson
        );
    }

    public TimelineEvent toDomain(TimelineEventEntity entity) {
        return toDomain(entity, Collections.emptySet());
    }

    public TimelineEvent toDomain(TimelineEventEntity entity, Set<String> availableEvidenceIds) {
        Map<String, Object> metadata = Collections.emptyMap();
        if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
            try {
                metadata = objectMapper.readValue(entity.getPayload(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize payload for timeline event {}: {}", entity.getTitle(), e.getMessage());
            }
        }

        boolean isPreFailure = false;
        if (metadata != null && metadata.containsKey("isPreFailure")) {
            Object val = metadata.get("isPreFailure");
            if (val instanceof Boolean b) {
                isPreFailure = b;
            } else if (val != null) {
                isPreFailure = Boolean.parseBoolean(val.toString());
            }
        } else {
            // Historical fallback when isPreFailure was not serialized:
            // Git commits and config changes prior to runtime error/exception are pre-failure
            String et = entity.getEventType();
            if ("COMMIT".equalsIgnoreCase(et) || "CONFIG_CHANGE".equalsIgnoreCase(et) || "CONFIG_DELTA".equalsIgnoreCase(et)) {
                isPreFailure = true;
            }
        }

        String evidenceId = null;
        if (entity.getEvidenceIds() != null && !entity.getEvidenceIds().isBlank()) {
            try {
                List<String> ids = objectMapper.readValue(entity.getEvidenceIds(), new TypeReference<List<String>>() {});
                if (!ids.isEmpty()) evidenceId = ids.get(0);
            } catch (Exception ignored) {}
        }

        // Canonical timeline evidence resolution:
        // If the referenced evidence ID does not exist in availableEvidenceIds,
        // resolve to the registered commit or root evidence
        if (evidenceId != null && availableEvidenceIds != null && !availableEvidenceIds.isEmpty() && !availableEvidenceIds.contains(evidenceId)) {
            if (evidenceId.startsWith("EVD-CFG-")) {
                String gitFallback = "EVD-GIT-" + evidenceId.substring("EVD-CFG-".length());
                if (availableEvidenceIds.contains(gitFallback)) {
                    log.debug("Resolved timeline evidence ID {} to registered commit evidence {}", evidenceId, gitFallback);
                    evidenceId = gitFallback;
                }
            } else if (evidenceId.startsWith("EVD-STACK-")) {
                if (availableEvidenceIds.contains("EVD-STACK-ROOT")) {
                    log.debug("Resolved timeline evidence ID {} to registered root stack trace evidence EVD-STACK-ROOT", evidenceId);
                    evidenceId = "EVD-STACK-ROOT";
                }
            } else if (evidenceId.startsWith("EVD-LOG-")) {
                if (availableEvidenceIds.contains("EVD-LOG-SIG")) {
                    log.debug("Resolved timeline evidence ID {} to registered error signature evidence EVD-LOG-SIG", evidenceId);
                    evidenceId = "EVD-LOG-SIG";
                }
            }
        }

        return new TimelineEvent(
                entity.getId().toString(),
                entity.getTimestamp(),
                entity.getEventType(),
                entity.getSource(),
                entity.getInvestigationId().toString(),
                entity.getTitle(),
                entity.getTitle(),
                isPreFailure,
                evidenceId,
                metadata
        );
    }
}
