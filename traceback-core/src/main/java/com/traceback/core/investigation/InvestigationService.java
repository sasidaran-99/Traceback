package com.traceback.core.investigation;

import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.domain.Incident;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.HypothesisStatus;
import com.traceback.core.domain.InvestigationEntity;
import com.traceback.core.domain.InvestigationHypothesisEntity;
import com.traceback.core.domain.TimelineEventEntity;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCollector;
import com.traceback.core.git.workingtree.GitWorkingTreeCollector;
import com.traceback.core.git.workingtree.WorkingTreeEvidence;
import com.traceback.core.git.workingtree.WorkingTreeSnapshot;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.logging.InvestigationLoggingContext;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService;
import com.traceback.core.persistence.InvestigationPersistenceMapper;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.repository.InvestigationHypothesisRepository;
import com.traceback.core.repository.InvestigationRepository;
import com.traceback.core.repository.InvestigationTimelineRepository;
import com.traceback.core.security.SecretRedactor;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.ClaimValidator;
import com.traceback.core.validation.DeterministicClaimValidator;
import com.traceback.core.validation.InvestigationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrator service executing end-to-end deterministic failure investigations with dual-store
 * (in-memory fast cache + durable PostgreSQL persistence) capabilities.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final GitCollector gitCollector;
    private final GitWorkingTreeCollector workingTreeCollector;
    private final StackTraceParser stackTraceParser;
    private final LogAnalyzer logAnalyzer;
    private final TimelineBuilder timelineBuilder;
    private final CorrelationEngine correlationEngine;
    private final EvidenceRegistry evidenceRegistry;

    private final Map<UUID, InvestigationResult> investigationStore = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private com.traceback.core.persistence.InvestigationPersistenceCoordinator persistenceCoordinator;

    @Autowired(required = false)
    private InvestigationDeduplicator deduplicator;

    @Autowired(required = false)
    private InvestigationRepository investigationRepository;

    @Autowired(required = false)
    private InvestigationEvidenceRepository evidenceRepository;

    @Autowired(required = false)
    private InvestigationClaimRepository claimRepository;

    @Autowired(required = false)
    private InvestigationTimelineRepository timelineRepository;

    @Autowired(required = false)
    private InvestigationAgentReportRepository agentReportRepository;

    @Autowired(required = false)
    private InvestigationHypothesisRepository hypothesisRepository;

    @Autowired(required = false)
    private ClaimValidator claimValidator = new DeterministicClaimValidator();

    @Autowired(required = false)
    private InvestigationPersistenceMapper persistenceMapper = new InvestigationPersistenceMapper();

    @Autowired
    public InvestigationService(GitCollector gitCollector,
                                GitWorkingTreeCollector workingTreeCollector,
                                StackTraceParser stackTraceParser,
                                LogAnalyzer logAnalyzer,
                                TimelineBuilder timelineBuilder,
                                CorrelationEngine correlationEngine,
                                EvidenceRegistry evidenceRegistry) {
        this.gitCollector = gitCollector;
        this.workingTreeCollector = workingTreeCollector != null ? workingTreeCollector : new GitWorkingTreeCollector(gitCollector, (List<String>) null);
        this.stackTraceParser = stackTraceParser;
        this.logAnalyzer = logAnalyzer;
        this.timelineBuilder = timelineBuilder;
        this.correlationEngine = correlationEngine;
        this.evidenceRegistry = evidenceRegistry;
    }

    public InvestigationService(GitCollector gitCollector,
                                StackTraceParser stackTraceParser,
                                LogAnalyzer logAnalyzer,
                                TimelineBuilder timelineBuilder,
                                CorrelationEngine correlationEngine,
                                EvidenceRegistry evidenceRegistry) {
        this(gitCollector, new GitWorkingTreeCollector(gitCollector, (List<String>) null),
                stackTraceParser, logAnalyzer, timelineBuilder, correlationEngine, evidenceRegistry);
    }

    public InvestigationService() {
        this(new GitCollector(), new StackTraceParser(), new LogAnalyzer(),
                new TimelineBuilder(), new CorrelationEngine(), new ConcreteEvidenceRegistry());
    }

    /**
     * Executes an end-to-end deterministic investigation with default RUNTIME_FAILURE mode.
     */
    public InvestigationResult startInvestigation(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits
    ) {
        return startInvestigation(repositoryPath, stackTrace, logFilePath, logContent,
                serviceName, environment, windowMinutes, maxCommits, InvestigationMode.RUNTIME_FAILURE);
    }

    /**
     * Executes an end-to-end deterministic investigation with specified InvestigationMode, defaulting to bounded deduplication.
     */
    public InvestigationResult startInvestigation(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits,
            InvestigationMode investigationMode
    ) {
        return startInvestigation(repositoryPath, stackTrace, logFilePath, logContent,
                serviceName, environment, windowMinutes, maxCommits, investigationMode, false);
    }

    /**
     * Executes an end-to-end deterministic investigation with optional forced independent execution.
     * When forceNew is false, reuses an existing matching durable investigation completed within 5 minutes.
     */
    public InvestigationResult startInvestigation(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits,
            InvestigationMode investigationMode,
            boolean forceNew
    ) {
        return startInvestigationWithOutcome(repositoryPath, stackTrace, logFilePath, logContent,
                serviceName, environment, windowMinutes, maxCommits, investigationMode, forceNew).value();
    }

    /**
     * Executes an end-to-end deterministic investigation returning both the result and deduplication outcome.
     */
    public InvestigationDeduplicator.DeduplicationOutcome<InvestigationResult> startInvestigationWithOutcome(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits,
            InvestigationMode investigationMode,
            boolean forceNew
    ) {
        if (forceNew) {
            InvestigationResult result = executeInvestigationInternal(repositoryPath, stackTrace, logFilePath, logContent,
                    serviceName, environment, windowMinutes, maxCommits, investigationMode);
            return new InvestigationDeduplicator.DeduplicationOutcome<>(result, false);
        }

        String fingerprint = RequestFingerprint.compute(
                repositoryPath, stackTrace, logFilePath, logContent,
                serviceName, environment, windowMinutes, maxCommits, investigationMode
        );

        return getOrCreateDeduplicator().executeInvestigationWithOutcome(fingerprint, () ->
                executeInvestigationInternal(repositoryPath, stackTrace, logFilePath, logContent,
                        serviceName, environment, windowMinutes, maxCommits, investigationMode)
        );
    }

    private InvestigationResult executeInvestigationInternal(
            String repositoryPath,
            String stackTrace,
            String logFilePath,
            String logContent,
            String serviceName,
            String environment,
            int windowMinutes,
            int maxCommits,
            InvestigationMode investigationMode
    ) {
        Instant startTime = Instant.now();
        UUID investigationId = UUID.randomUUID();

        try (var ignored = InvestigationLoggingContext.withInvestigationId(investigationId)) {
            String svc = serviceName != null ? serviceName : "payment-service";
            String env = environment != null ? environment : "production";
            int window = windowMinutes > 0 ? windowMinutes : 60;
            int max = maxCommits > 0 ? maxCommits : 50;
            InvestigationMode mode = investigationMode != null ? investigationMode : InvestigationMode.RUNTIME_FAILURE;

            log.info("Starting investigation {} (mode={}) for service={}, env={}, repo={}",
                    investigationId, mode, svc, env, repositoryPath);

        // Security check: validate logFilePath before any operations
        Path normalizedLogPath = null;
        if (logFilePath != null && !logFilePath.isBlank()) {
            Path path = Path.of(logFilePath);
            normalizedLogPath = path.toAbsolutePath().normalize();
            if (gitCollector != null && !gitCollector.isPathAuthorized(normalizedLogPath)) {
                log.warn("Access denied: Log file path {} is outside authorized roots", logFilePath);
                throw new SecurityException("Access denied: Log file path is not within the authorized repository roots");
            }
            if (Files.exists(normalizedLogPath)) {
                try {
                    long size = Files.size(normalizedLogPath);
                    if (size > 5 * 1024 * 1024) {
                        throw new IllegalArgumentException("Log file exceeds maximum allowed size of 5MB: " + size + " bytes");
                    }
                } catch (IOException e) {
                    log.error("Failed to check size of log file: {}", logFilePath, e);
                }
            }
        }

        // 1. Collect Git commits
        List<CommitEvidence> commits = Collections.emptyList();
        if (repositoryPath != null && !repositoryPath.isBlank()) {
            commits = gitCollector.collectCommits(repositoryPath, max);
        }

        // 1b. Collect uncommitted working tree state and discrepancies
        WorkingTreeSnapshot workingTreeSnapshot = null;
        if (workingTreeCollector != null && repositoryPath != null && !repositoryPath.isBlank()) {
            try {
                workingTreeSnapshot = workingTreeCollector.collectWorkingTree(repositoryPath);
                if (workingTreeSnapshot != null && workingTreeSnapshot.hasChanges()) {
                    for (WorkingTreeEvidence wte : workingTreeSnapshot.changes()) {
                        evidenceRegistry.registerItem(wte.toEvidenceItem(investigationId), investigationId);
                    }
                }
            } catch (SecurityException | IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Working tree inspection skipped or failed for {}: {}", repositoryPath, e.getMessage());
            }
        }

        String invStr = investigationId != null ? investigationId.toString() : "GLOBAL";

        // 2. Parse Stack Trace if provided
        ParsedException parsedStack = null;
        if (stackTrace != null && !stackTrace.isBlank()) {
            parsedStack = stackTraceParser.parse(stackTrace).orElse(null);
            if (parsedStack != null) {
                evidenceRegistry.registerItem(EvidenceItem.create(
                        "EVD-STACK-ROOT",
                        EvidenceType.STACK_TRACE,
                        "STACK_TRACE",
                        parsedStack.getRootCause().exceptionType(),
                        startTime,
                        "Root exception: " + parsedStack.getRootCause().exceptionType() + ": " + parsedStack.getRootCause().message(),
                        Map.of(
                                "type", parsedStack.getRootCause().exceptionType(),
                                "investigationId", invStr,
                                "originatingInvestigationId", invStr
                        )
                ), investigationId);
            }
        }

        // 3. Analyze Logs
        LogEvidence logEvidence = null;
        if (normalizedLogPath != null) {
            try {
                if (Files.exists(normalizedLogPath)) {
                    logEvidence = logAnalyzer.analyzeFile(normalizedLogPath);
                } else {
                    log.warn("Specified log file does not exist: {}", logFilePath);
                }
            } catch (IOException e) {
                log.error("Failed to read log file: {}", logFilePath, e);
            }
        } else if (logContent != null && !logContent.isBlank()) {
            logEvidence = logAnalyzer.analyzeContent(logContent);
        }

        if (logEvidence != null && logEvidence.primaryErrorSignature() != null) {
            evidenceRegistry.registerItem(EvidenceItem.create(
                    "EVD-LOG-SIG",
                    EvidenceType.ERROR_SIGNATURE,
                    "LOG",
                    logEvidence.primaryErrorSignature().toKey(),
                    logEvidence.firstObservedFailure(),
                    "Primary error signature: " + logEvidence.primaryErrorSignature().exceptionType(),
                    Map.of(
                            "signature", logEvidence.primaryErrorSignature().toKey(),
                            "count", logEvidence.totalErrors(),
                            "investigationId", invStr,
                            "originatingInvestigationId", invStr
                    )
            ), investigationId);
        }

        // 4. Build Timeline
        Instant failureOnset = logEvidence != null ? logEvidence.firstObservedFailure() : null;
        List<TimelineEvent> timeline = timelineBuilder.buildTimeline(commits, logEvidence, parsedStack, failureOnset, workingTreeSnapshot);

        // 5. Correlate and Score Hypotheses
        Incident simulatedIncident = new Incident(
                investigationId,
                "INC-" + investigationId.toString().substring(0, 8).toUpperCase(),
                "Deterministic Investigation: " + svc,
                svc,
                env,
                "HIGH",
                "INVESTIGATING",
                failureOnset != null ? failureOnset : startTime,
                null,
                null,
                null
        );

        CorrelationResult correlationResult = correlationEngine.correlate(
                simulatedIncident,
                commits,
                logEvidence,
                parsedStack,
                workingTreeSnapshot,
                evidenceRegistry,
                window
        );

        // 6. Assemble Result (Strictly investigation-scoped evidence)
        List<EvidenceItem> allEvidence = evidenceRegistry.findByInvestigationId(investigationId);
        ParsedException primaryException = parsedStack != null ? parsedStack : (logEvidence != null ? logEvidence.primaryException() : null);
        int logErrors = logEvidence != null ? logEvidence.totalErrors() : 0;
        int totalErrors = logErrors > 0 ? logErrors : (parsedStack != null ? 1 : 0);

        // Canonical timeline evidence resolution: ensure all timeline events resolve against registered evidence
        Set<String> validEvidenceIds = allEvidence.stream()
                .map(EvidenceItem::evidenceId)
                .collect(Collectors.toSet());
        List<TimelineEvent> sanitizedTimeline = timeline.stream().map(te -> {
            String eid = te.evidenceId();
            if (eid != null && !validEvidenceIds.contains(eid)) {
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

        InvestigationResult result = new InvestigationResult(
                investigationId,
                svc,
                env,
                startTime,
                Instant.now(),
                correlationResult.evidenceSufficient() ? "COMPLETED" : "INSUFFICIENT_EVIDENCE",
                commits.size(),
                totalErrors,
                primaryException,
                sanitizedTimeline,
                allEvidence,
                correlationResult.relationships(),
                correlationResult.rankedHypotheses(),
                correlationResult.supportingEvidenceByCommitSha(),
                correlationResult.counterEvidenceByCommitSha(),
                correlationResult.confidenceBreakdownsByCommitSha(),
                correlationResult.claims(),
                correlationResult.evidenceSufficient(),
                mode
        );

        // Durable PostgreSQL persistence before caching
        boolean persisted = persistInvestigation(result, repositoryPath);
        if (persisted) {
            result = result.withPersistenceStatus(true, "DURABLE");
        } else {
            result = result.withPersistenceStatus(false, "PERSISTENCE_FAILED");
        }

        investigationStore.put(investigationId, result);

        log.info("Investigation {} completed. Hypotheses: {}, Claims: {}, EvidenceSufficient: {}, PersistedDurable: {}",
                investigationId, result.hypotheses().size(), result.claims().size(), result.evidenceSufficient(), result.persistedDurable());

        return result;
        }
    }

    private boolean persistInvestigation(InvestigationResult result, String repositoryPath) {
        if (persistenceCoordinator == null) {
            log.warn("InvestigationPersistenceCoordinator is unavailable; skipping durable persistence to prevent uncoordinated/non-transactional writes for investigation {}", result.investigationId());
            return false;
        }
        try {
            return persistenceCoordinator.persistInvestigationCoordinated(result, repositoryPath);
        } catch (Exception e) {
            log.error("Failed to persist investigation {} to PostgreSQL: {}", result.investigationId(), SecretRedactor.redactText(e.getMessage()));
            return false;
        }
    }

    public void cacheInvestigation(InvestigationResult result) {
        if (result != null && result.investigationId() != null) {
            investigationStore.put(result.investigationId(), result);
        }
    }

    public Optional<InvestigationResult> getInvestigation(UUID id) {
        InvestigationResult cached = investigationStore.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }

        if (investigationRepository != null) {
            Optional<InvestigationEntity> entityOpt = investigationRepository.findById(id);
            if (entityOpt.isPresent()) {
                InvestigationEntity entity = entityOpt.get();
                List<TimelineEvent> timeline = getTimeline(id);
                List<EvidenceItem> evidence = getEvidence(id);
                List<InvestigationClaim> claims = getClaims(id);
                List<Hypothesis> hypotheses = getHypotheses(id);

                InvestigationResult reconstructed = persistenceMapper.toDomain(entity, timeline, evidence, claims, hypotheses)
                        .withPersistenceStatus(true, "DURABLE");
                investigationStore.put(id, reconstructed);
                return Optional.of(reconstructed);
            }
        }

        return Optional.empty();
    }

    public List<Hypothesis> getHypotheses(UUID id) {
        InvestigationResult cached = investigationStore.get(id);
        if (cached != null && cached.hypotheses() != null && !cached.hypotheses().isEmpty()) {
            return cached.hypotheses();
        }

        if (hypothesisRepository != null) {
            List<InvestigationHypothesisEntity> entities = hypothesisRepository.findByInvestigationIdOrderByRankAsc(id);
            if (!entities.isEmpty()) {
                return entities.stream().map(persistenceMapper::toDomain).toList();
            }
        }

        // Recover evaluated hypotheses for historical investigations
        List<Hypothesis> recovered = recoverHistoricalHypotheses(id);
        if (!recovered.isEmpty()) {
            if (hypothesisRepository != null) {
                for (Hypothesis h : recovered) {
                    try {
                        hypothesisRepository.save(persistenceMapper.toEntity(h, id));
                    } catch (Exception e) {
                        log.debug("Historical hypothesis persistence skipped: {}", e.getMessage());
                    }
                }
            }
            return recovered;
        }

        return Collections.emptyList();
    }

    private List<Hypothesis> recoverHistoricalHypotheses(UUID id) {
        if (agentReportRepository == null) {
            return Collections.emptyList();
        }
        Optional<AgentReportEntity> reportOpt = agentReportRepository.findLatestByInvestigationId(id);
        if (reportOpt.isEmpty()) {
            return Collections.emptyList();
        }
        AgentReportEntity report = reportOpt.get();
        List<EvidenceItem> evidence = getEvidence(id);

        List<Hypothesis> hypotheses = new ArrayList<>();

        // Rank 1 hypothesis is the primary suspect
        ConfidenceBreakdown breakdown = null;
        double confidence = 0.75;
        if ("CONFIRMED".equalsIgnoreCase(report.getVerdict())) {
            confidence = 0.95;
        } else if ("INSUFFICIENT_EVIDENCE".equalsIgnoreCase(report.getVerdict())) {
            confidence = 0.20;
        }

        if (report.getConfidenceBreakdown() != null && !report.getConfidenceBreakdown().isBlank()) {
            try {
                breakdown = persistenceMapper.getObjectMapper().readValue(report.getConfidenceBreakdown(), ConfidenceBreakdown.class);
                if (breakdown != null && breakdown.normalizedScore() > 0) {
                    confidence = breakdown.normalizedScore();
                }
            } catch (Exception ignored) {}
        }

        HypothesisStatus status = HypothesisStatus.PROPOSED;
        if ("CONFIRMED".equalsIgnoreCase(report.getVerdict())) {
            status = HypothesisStatus.CONFIRMED;
        } else if ("PROBABLE".equalsIgnoreCase(report.getVerdict())) {
            status = HypothesisStatus.PROPOSED;
        } else if ("INSUFFICIENT_EVIDENCE".equalsIgnoreCase(report.getVerdict())) {
            status = HypothesisStatus.REJECTED;
        }

        List<String> supIds = persistenceMapper.deserializeEvidenceIds(report.getSupportingEvidenceIds());
        List<String> contraIds = persistenceMapper.deserializeEvidenceIds(report.getContradictoryEvidenceIds());

        String suspectedSha = null;
        for (String eid : supIds) {
            if (eid.startsWith("EVD-GIT-")) {
                suspectedSha = eid.substring("EVD-GIT-".length());
                break;
            }
        }

        Hypothesis primary = new Hypothesis(
                UUID.randomUUID(),
                null,
                null,
                1,
                confidence,
                status,
                report.getPrimarySuspectedRootCause() != null ? report.getPrimarySuspectedRootCause() : "Suspected root cause",
                "Execute recommended verification steps",
                contraIds.isEmpty() ? null : String.join(", ", contraIds),
                supIds,
                contraIds,
                List.of("RULE_AGENT_PRIMARY_SUSPECT"),
                Map.of("RULE_AGENT_PRIMARY_SUSPECT", "Identified by autonomous investigation agent"),
                Collections.emptyList(),
                "Primary candidate hypothesis ranked 1 based on verified evidence correlation",
                breakdown
        );
        hypotheses.add(primary);

        // Alternative candidate commits as secondary hypotheses
        int rank = 2;
        for (EvidenceItem item : evidence) {
            if (item.evidenceType() == EvidenceType.COMMIT && item.evidenceId().startsWith("EVD-GIT-")) {
                String sha = item.evidenceId().substring("EVD-GIT-".length());
                if (suspectedSha != null && (sha.equalsIgnoreCase(suspectedSha) || suspectedSha.startsWith(sha) || sha.startsWith(suspectedSha))) {
                    continue; // Skip primary suspect
                }
                Hypothesis alt = new Hypothesis(
                        UUID.randomUUID(),
                        null,
                        null,
                        rank++,
                        0.35,
                        HypothesisStatus.REJECTED,
                        "Candidate commit " + sha + ": " + item.summary(),
                        "Review commit changes and verify unaffected runtime pathways",
                        "Superseded or lower heuristic correlation score",
                        List.of(item.evidenceId()),
                        Collections.emptyList(),
                        List.of("RULE_ALTERNATIVE_CANDIDATE"),
                        Map.of("RULE_ALTERNATIVE_CANDIDATE", "Candidate commit in investigation analysis window"),
                        Collections.emptyList(),
                        "Alternative candidate hypothesis ranked " + (rank - 1),
                        null
                );
                hypotheses.add(alt);
            }
        }

        return hypotheses;
    }

    public List<TimelineEvent> getTimeline(UUID id) {
        InvestigationResult cached = investigationStore.get(id);
        if (cached != null && cached.timeline() != null && !cached.timeline().isEmpty()) {
            return cached.timeline();
        }

        if (timelineRepository != null) {
            List<TimelineEventEntity> entities = timelineRepository.findByInvestigationIdOrderByTimestampAsc(id);
            if (!entities.isEmpty()) {
                Set<String> validEvidenceIds = getEvidence(id).stream()
                        .map(EvidenceItem::evidenceId)
                        .collect(Collectors.toSet());
                return entities.stream().map(e -> persistenceMapper.toDomain(e, validEvidenceIds)).toList();
            }
        }

        return Collections.emptyList();
    }

    public List<EvidenceItem> getEvidence(UUID id) {
        InvestigationResult cached = investigationStore.get(id);
        if (cached != null && cached.evidenceItems() != null && !cached.evidenceItems().isEmpty()) {
            return cached.evidenceItems();
        }

        if (evidenceRepository != null) {
            List<EvidenceItemEntity> entities = evidenceRepository.findByInvestigationId(id);
            if (!entities.isEmpty()) {
                return entities.stream().map(persistenceMapper::toDomain).toList();
            }

            // Fallback: Resolve evidence items referenced by the investigation's claims, timeline, or report
            // scoped strictly to this investigation
            Set<String> referencedIds = new LinkedHashSet<>();
            if (claimRepository != null) {
                for (ClaimEntity ce : claimRepository.findByInvestigationId(id)) {
                    if (ce.getSupportingEvidenceIds() != null && !ce.getSupportingEvidenceIds().isBlank()) {
                        referencedIds.addAll(persistenceMapper.deserializeEvidenceIds(ce.getSupportingEvidenceIds()));
                    }
                }
            }
            if (timelineRepository != null) {
                for (TimelineEventEntity te : timelineRepository.findByInvestigationIdOrderByTimestampAsc(id)) {
                    if (te.getEvidenceIds() != null && !te.getEvidenceIds().isBlank()) {
                        referencedIds.addAll(persistenceMapper.deserializeEvidenceIds(te.getEvidenceIds()));
                    }
                }
            }
            if (agentReportRepository != null) {
                agentReportRepository.findLatestByInvestigationId(id).ifPresent(re -> {
                    if (re.getSupportingEvidenceIds() != null && !re.getSupportingEvidenceIds().isBlank()) {
                        referencedIds.addAll(persistenceMapper.deserializeEvidenceIds(re.getSupportingEvidenceIds()));
                    }
                });
            }

            if (!referencedIds.isEmpty()) {
                List<EvidenceItemEntity> matched = evidenceRepository.findByInvestigationIdAndEvidenceIdIn(id, referencedIds);
                if (!matched.isEmpty()) {
                    return matched.stream().map(persistenceMapper::toDomain).toList();
                }
            }
        }

        return Collections.emptyList();
    }

    public List<com.traceback.core.validation.InvestigationClaim> getClaims(UUID id) {
        if (agentReportRepository != null) {
            Optional<AgentReportEntity> reportOpt = agentReportRepository.findLatestByInvestigationId(id);
            if (reportOpt.isPresent() && reportOpt.get().getValidatedClaims() != null && !reportOpt.get().getValidatedClaims().isBlank()) {
                List<com.traceback.core.validation.InvestigationClaim> validated = persistenceMapper.deserializeValidatedClaims(reportOpt.get().getValidatedClaims());
                if (!validated.isEmpty()) {
                    return validated;
                }
            }
        }

        InvestigationResult cached = investigationStore.get(id);
        if (cached != null && cached.claims() != null && !cached.claims().isEmpty()) {
            return cached.claims();
        }

        if (claimRepository != null) {
            List<ClaimEntity> entities = claimRepository.findByInvestigationId(id);
            if (!entities.isEmpty()) {
                return entities.stream().map(persistenceMapper::toDomain).toList();
            }
        }

        return Collections.emptyList();
    }

    /**
     * Revalidates all stored claims for the specified investigation against its current
     * genuine evidence items using DeterministicClaimValidator.
     * Updates both investigation_claims and investigation_agent_reports.
     */
    @Transactional
    public List<InvestigationClaim> revalidateInvestigationClaims(UUID investigationId) {
        if (investigationId == null) {
            throw new IllegalArgumentException("investigationId cannot be null");
        }

        List<InvestigationClaim> revalidatedClaims = new ArrayList<>();

        // 1. Build an evidence registry scoped to the active investigation's genuine evidence
        EvidenceRegistry registry = new ConcreteEvidenceRegistry();
        if (evidenceRepository != null) {
            List<EvidenceItemEntity> entities = evidenceRepository.findByInvestigationId(investigationId);
            for (EvidenceItemEntity entity : entities) {
                EvidenceItem item = persistenceMapper.toDomain(entity);
                registry.registerItem(item, investigationId);
            }
        }

        // 2. Revalidate all claims in claimRepository
        if (claimRepository != null) {
            List<ClaimEntity> claimEntities = claimRepository.findByInvestigationId(investigationId);
            for (ClaimEntity ce : claimEntities) {
                InvestigationClaim domainClaim = persistenceMapper.toDomain(ce);
                InvestigationClaim validated = claimValidator.validateClaim(domainClaim, investigationId, registry);
                ce.setValidationStatus(validated.status().name());
                ce.setValidationExplanation(validated.explanation());
                claimRepository.save(ce);
                revalidatedClaims.add(validated);
            }
        }

        // 3. Update agent report validated_claims if present
        if (agentReportRepository != null && !revalidatedClaims.isEmpty()) {
            agentReportRepository.findLatestByInvestigationId(investigationId).ifPresent(report -> {
                try {
                    report.setValidatedClaims(persistenceMapper.getObjectMapper().writeValueAsString(revalidatedClaims));
                    agentReportRepository.save(report);
                } catch (Exception e) {
                    log.warn("Failed to serialize validated claims into agent report for {}: {}", investigationId, e.getMessage());
                }
            });
        }

        log.info("Revalidated {} claims for investigation {} via DeterministicClaimValidator.",
                revalidatedClaims.size(), investigationId);
        return revalidatedClaims;
    }

    public void setClaimValidator(ClaimValidator claimValidator) {
        this.claimValidator = claimValidator != null ? claimValidator : new DeterministicClaimValidator();
    }

    public List<InvestigationResult> getAllInvestigations() {
        if (investigationRepository != null) {
            List<InvestigationEntity> allEntities = investigationRepository.findAllByOrderByCreatedAtDesc();
            List<InvestigationResult> results = new ArrayList<>();
            for (InvestigationEntity entity : allEntities) {
                InvestigationResult cached = investigationStore.get(entity.getId());
                if (cached != null) {
                    results.add(cached);
                } else {
                    List<TimelineEvent> timeline = getTimeline(entity.getId());
                    List<EvidenceItem> evidence = getEvidence(entity.getId());
                    List<InvestigationClaim> claims = getClaims(entity.getId());
                    List<Hypothesis> hypotheses = getHypotheses(entity.getId());
                    InvestigationResult reconstructed = persistenceMapper.toDomain(entity, timeline, evidence, claims, hypotheses)
                        .withPersistenceStatus(true, "DURABLE");
                    investigationStore.put(entity.getId(), reconstructed);
                    results.add(reconstructed);
                }
            }
            return results;
        }

        return List.copyOf(investigationStore.values());
    }

    public EvidenceRegistry getEvidenceRegistry() {
        return evidenceRegistry;
    }

    public GitWorkingTreeCollector getWorkingTreeCollector() {
        return workingTreeCollector;
    }

    public void setPersistenceCoordinator(com.traceback.core.persistence.InvestigationPersistenceCoordinator persistenceCoordinator) {
        this.persistenceCoordinator = persistenceCoordinator;
    }

    public com.traceback.core.persistence.InvestigationPersistenceCoordinator getPersistenceCoordinator() {
        return persistenceCoordinator;
    }

    // Setters for test harness
    public void setInvestigationRepository(InvestigationRepository investigationRepository) {
        this.investigationRepository = investigationRepository;
    }

    public void setEvidenceRepository(InvestigationEvidenceRepository evidenceRepository) {
        this.evidenceRepository = evidenceRepository;
    }

    public void setClaimRepository(InvestigationClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    public void setTimelineRepository(InvestigationTimelineRepository timelineRepository) {
        this.timelineRepository = timelineRepository;
    }

    public void setAgentReportRepository(InvestigationAgentReportRepository agentReportRepository) {
        this.agentReportRepository = agentReportRepository;
    }

    public void setHypothesisRepository(InvestigationHypothesisRepository hypothesisRepository) {
        this.hypothesisRepository = hypothesisRepository;
    }

    public void setPersistenceMapper(InvestigationPersistenceMapper persistenceMapper) {
        this.persistenceMapper = persistenceMapper;
    }

    public void setDeduplicator(InvestigationDeduplicator deduplicator) {
        this.deduplicator = deduplicator;
    }

    public InvestigationDeduplicator getDeduplicator() {
        return deduplicator;
    }

    private InvestigationDeduplicator getOrCreateDeduplicator() {
        if (deduplicator != null) {
            return deduplicator;
        }
        deduplicator = new InvestigationDeduplicator();
        return deduplicator;
    }
}
