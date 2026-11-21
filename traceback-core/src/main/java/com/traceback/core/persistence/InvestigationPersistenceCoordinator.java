package com.traceback.core.persistence;

import com.traceback.core.agent.AgentInvestigationStep;
import com.traceback.core.agent.InvestigationAgentReport;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.AgentStepEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.InvestigationHypothesisEntity;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.investigation.InvestigationResult;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationAgentStepRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.InvestigationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinated persistence service for TraceBack investigations and agent reports.
 * Enforces atomic transactions across multi-entity persistence operations:
 * - Persisting agent reports, audit steps, claims, and updating parent investigations
 * - Persisting investigation records, evidence items, claims, timeline events, and hypotheses
 *
 * Guaranteed to execute via Spring transactional proxy so that failures roll back
 * all participating database records together, eliminating partial commits or orphaned records.
 */
@Service
public class InvestigationPersistenceCoordinator {

    private static final Logger log = LoggerFactory.getLogger(InvestigationPersistenceCoordinator.class);

    private final InvestigationRepository investigationRepository;
    private final InvestigationEvidenceRepository evidenceRepository;
    private final InvestigationClaimRepository claimRepository;
    private final InvestigationTimelineRepository timelineRepository;
    private final InvestigationHypothesisRepository hypothesisRepository;
    private final InvestigationAgentReportRepository agentReportRepository;
    private final InvestigationAgentStepRepository agentStepRepository;
    private final InvestigationPersistenceMapper persistenceMapper;

    @Autowired
    public InvestigationPersistenceCoordinator(
            @Autowired(required = false) InvestigationRepository investigationRepository,
            @Autowired(required = false) InvestigationEvidenceRepository evidenceRepository,
            @Autowired(required = false) InvestigationClaimRepository claimRepository,
            @Autowired(required = false) InvestigationTimelineRepository timelineRepository,
            @Autowired(required = false) InvestigationHypothesisRepository hypothesisRepository,
            @Autowired(required = false) InvestigationAgentReportRepository agentReportRepository,
            @Autowired(required = false) InvestigationAgentStepRepository agentStepRepository,
            @Autowired(required = false) InvestigationPersistenceMapper persistenceMapper
    ) {
        this.investigationRepository = investigationRepository;
        this.evidenceRepository = evidenceRepository;
        this.claimRepository = claimRepository;
        this.timelineRepository = timelineRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.agentReportRepository = agentReportRepository;
        this.agentStepRepository = agentStepRepository;
        this.persistenceMapper = persistenceMapper != null ? persistenceMapper : new InvestigationPersistenceMapper();
    }

    /**
     * Persists an autonomous agent report, its steps, validated claims, and updates the parent
     * investigation atomically. Any failure in any entity triggers a rollback of the whole transaction.
     *
     * @param report the agent report to persist
     * @return true if persistence succeeded across all entities; false if skipped due to unconfigured repositories
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean persistReportCoordinated(InvestigationAgentReport report) {
        if (report == null) {
            return false;
        }
        if (agentReportRepository == null && agentStepRepository == null
                && claimRepository == null && investigationRepository == null) {
            return false;
        }

        UUID invId = report.investigationId();

        // 1. Agent Report Entity
        if (agentReportRepository != null) {
            agentReportRepository.save(persistenceMapper.toEntity(report));
        }

        // 2. Agent Steps
        if (agentStepRepository != null && report.investigationSteps() != null) {
            for (AgentInvestigationStep step : report.investigationSteps()) {
                Optional<AgentStepEntity> existing = agentStepRepository.findByInvestigationIdAndStepNumber(invId, step.stepNumber());
                AgentStepEntity entity = persistenceMapper.toEntity(
                        step,
                        invId,
                        existing.map(AgentStepEntity::getId).orElse(null)
                );
                agentStepRepository.save(entity);
            }

            // Clean up trailing steps if rerun produced fewer steps
            List<AgentStepEntity> existingSteps = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId);
            for (AgentStepEntity se : existingSteps) {
                if (se.getStepNumber() > report.investigationSteps().size()) {
                    agentStepRepository.delete(se);
                }
            }
        }

        // 3. Claims
        if (claimRepository != null && report.validatedClaims() != null) {
            for (InvestigationClaim claim : report.validatedClaims()) {
                Optional<ClaimEntity> existing = claimRepository.findByInvestigationIdAndClaimId(invId, claim.claimId());
                String claimType = claim.claimId().startsWith("CLAIM-AGENT-PRIMARY") ? "AGENT_SYNTHESIZED_PRIMARY" : "DETERMINISTIC";
                ClaimEntity claimEntity = persistenceMapper.toEntity(claim, invId, claimType);
                existing.ifPresent(e -> claimEntity.setId(e.getId()));
                claimRepository.save(claimEntity);
            }
        }

        // 4. Update Parent Investigation Entity
        if (investigationRepository != null) {
            Optional<InvestigationEntity> invOpt = investigationRepository.findById(invId);
            if (invOpt.isPresent()) {
                InvestigationEntity entity = invOpt.get();
                entity.setReportId(report.reportId());
                entity.setVerdict(report.finalStatus().name());
                if (report.deterministicConfidenceBreakdown() != null) {
                    entity.setConfidenceScore(report.deterministicConfidenceBreakdown().normalizedScore());
                }
                investigationRepository.save(entity);
            }
        }

        log.info("Persisted agent report {} for investigation {} coordinated.", report.reportId(), invId);
        return true;
    }

    /**
     * Persists a deterministic investigation result and all associated entities atomically.
     * Enforces evidence provenance and genuine/global checks. Any failure triggers a complete rollback.
     *
     * @param result the investigation result
     * @param repositoryPath the repository path
     * @return true if all entities persisted successfully; false if skipped due to null repository
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean persistInvestigationCoordinated(InvestigationResult result, String repositoryPath) {
        if (result == null || investigationRepository == null) {
            return false;
        }

        UUID invId = result.investigationId();

        // 1. Investigation entity
        InvestigationEntity entity = persistenceMapper.toEntity(result, repositoryPath);
        investigationRepository.save(entity);

        // 2. Evidence items (strict genuine/global checks)
        if (evidenceRepository != null && result.evidenceItems() != null) {
            for (EvidenceItem item : result.evidenceItems()) {
                boolean isGenuineActive = item.isGenuinelyCollectedFor(invId);
                boolean isGlobal = item.isGlobal() || "GLOBAL".equalsIgnoreCase(String.valueOf(item.metadata().get("investigationId")));

                if (isGenuineActive) {
                    if (!evidenceRepository.existsByInvestigationIdAndEvidenceId(invId, item.evidenceId())) {
                        evidenceRepository.save(persistenceMapper.toEntity(item, invId));
                    }
                } else if (isGlobal) {
                    if (!evidenceRepository.existsByInvestigationIdAndEvidenceId(invId, item.evidenceId())) {
                        evidenceRepository.save(persistenceMapper.toEntity(item, invId));
                    }
                } else {
                    log.warn("Strict persistence rejection: Evidence item {} (owner: {}, origin: {}) is foreign or non-genuine for investigation {}",
                            item.evidenceId(), item.investigationId(), item.originatingInvestigationId(), invId);
                }
            }
        }

        // 3. Claims
        if (claimRepository != null && result.claims() != null) {
            for (InvestigationClaim claim : result.claims()) {
                String type = claim.claimId().startsWith("CLM-WT") ? "WORKING_TREE_ANOMALY" : "DETERMINISTIC_CORRELATION";
                Optional<ClaimEntity> existing = claimRepository.findByInvestigationIdAndClaimId(invId, claim.claimId());
                ClaimEntity claimEntity = persistenceMapper.toEntity(claim, invId, type);
                existing.ifPresent(e -> claimEntity.setId(e.getId()));
                claimRepository.save(claimEntity);
            }
        }

        // 4. Timeline
        if (timelineRepository != null && result.timeline() != null) {
            for (TimelineEvent event : result.timeline()) {
                timelineRepository.save(persistenceMapper.toEntity(event, invId));
            }
        }

        // 5. Hypotheses
        if (hypothesisRepository != null && result.hypotheses() != null) {
            for (Hypothesis hypothesis : result.hypotheses()) {
                Optional<InvestigationHypothesisEntity> existing = hypothesisRepository.findByInvestigationIdAndHypothesisId(invId, hypothesis.getId());
                InvestigationHypothesisEntity hypEntity = persistenceMapper.toEntity(hypothesis, invId);
                existing.ifPresent(e -> hypEntity.setId(e.getId()));
                hypothesisRepository.save(hypEntity);
            }
        }

        log.info("Persisted investigation {} to PostgreSQL coordinated.", invId);
        return true;
    }

    // Getters for inspecting participating repositories in tests or diagnostics
    public InvestigationRepository getInvestigationRepository() {
        return investigationRepository;
    }

    public InvestigationEvidenceRepository getEvidenceRepository() {
        return evidenceRepository;
    }

    public InvestigationClaimRepository getClaimRepository() {
        return claimRepository;
    }

    public InvestigationTimelineRepository getTimelineRepository() {
        return timelineRepository;
    }

    public InvestigationHypothesisRepository getHypothesisRepository() {
        return hypothesisRepository;
    }

    public InvestigationAgentReportRepository getAgentReportRepository() {
        return agentReportRepository;
    }

    public InvestigationAgentStepRepository getAgentStepRepository() {
        return agentStepRepository;
    }

    public InvestigationPersistenceMapper getPersistenceMapper() {
        return persistenceMapper;
    }
}
