package com.traceback.core.investigation;

import com.traceback.core.correlation.CorrelationEngine;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Incident;
import com.traceback.core.evidence.ConcreteEvidenceRegistry;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.git.GitCollector;
import com.traceback.core.log.LogAnalyzer;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.stacktrace.StackTraceParser;
import com.traceback.core.timeline.TimelineBuilder;
import com.traceback.core.timeline.TimelineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrator service executing end-to-end deterministic failure investigations.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final GitCollector gitCollector;
    private final StackTraceParser stackTraceParser;
    private final LogAnalyzer logAnalyzer;
    private final TimelineBuilder timelineBuilder;
    private final CorrelationEngine correlationEngine;
    private final EvidenceRegistry evidenceRegistry;

    private final Map<UUID, InvestigationResult> investigationStore = new ConcurrentHashMap<>();

    public InvestigationService(GitCollector gitCollector,
                                StackTraceParser stackTraceParser,
                                LogAnalyzer logAnalyzer,
                                TimelineBuilder timelineBuilder,
                                CorrelationEngine correlationEngine,
                                EvidenceRegistry evidenceRegistry) {
        this.gitCollector = gitCollector;
        this.stackTraceParser = stackTraceParser;
        this.logAnalyzer = logAnalyzer;
        this.timelineBuilder = timelineBuilder;
        this.correlationEngine = correlationEngine;
        this.evidenceRegistry = evidenceRegistry;
    }

    public InvestigationService() {
        this(new GitCollector(), new StackTraceParser(), new LogAnalyzer(),
                new TimelineBuilder(), new CorrelationEngine(), new ConcreteEvidenceRegistry());
    }

    /**
     * Executes an end-to-end deterministic investigation.
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
        Instant startTime = Instant.now();
        UUID investigationId = UUID.randomUUID();
        String svc = serviceName != null ? serviceName : "payment-service";
        String env = environment != null ? environment : "production";
        int window = windowMinutes > 0 ? windowMinutes : 60;
        int max = maxCommits > 0 ? maxCommits : 50;

        log.info("Starting investigation {} for service={}, env={}, repo={}", investigationId, svc, env, repositoryPath);

        // 1. Collect Git commits
        List<CommitEvidence> commits = gitCollector.collectCommits(repositoryPath, max);

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
                        Map.of("type", parsedStack.getRootCause().exceptionType())
                ));
            }
        }

        // 3. Analyze Logs
        LogEvidence logEvidence = null;
        if (logFilePath != null && !logFilePath.isBlank()) {
            Path path = Path.of(logFilePath);
            try {
                if (Files.exists(path)) {
                    logEvidence = logAnalyzer.analyzeFile(path);
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
                    Map.of("signature", logEvidence.primaryErrorSignature().toKey(), "count", logEvidence.totalErrors())
            ));
        }

        // 4. Build Timeline
        Instant failureOnset = logEvidence != null ? logEvidence.firstObservedFailure() : null;
        List<TimelineEvent> timeline = timelineBuilder.buildTimeline(commits, logEvidence, parsedStack, failureOnset);

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
                evidenceRegistry,
                window
        );

        // 6. Assemble Result
        List<EvidenceItem> allEvidence = evidenceRegistry.findAllItems();
        ParsedException primaryException = parsedStack != null ? parsedStack : (logEvidence != null ? logEvidence.primaryException() : null);
        int totalErrors = logEvidence != null ? logEvidence.totalErrors() : (parsedStack != null ? 1 : 0);

        InvestigationResult result = new InvestigationResult(
                investigationId,
                svc,
                env,
                startTime,
                Instant.now(),
                "COMPLETED",
                commits.size(),
                totalErrors,
                primaryException,
                timeline,
                allEvidence,
                correlationResult.relationships(),
                correlationResult.rankedHypotheses(),
                correlationResult.supportingEvidenceByCommitSha(),
                correlationResult.counterEvidenceByCommitSha()
        );

        investigationStore.put(investigationId, result);
        log.info("Investigation {} completed. Hypotheses generated: {}", investigationId, result.hypotheses().size());

        return result;
    }

    public Optional<InvestigationResult> getInvestigation(UUID id) {
        return Optional.ofNullable(investigationStore.get(id));
    }

    public List<TimelineEvent> getTimeline(UUID id) {
        return getInvestigation(id).map(InvestigationResult::timeline).orElse(Collections.emptyList());
    }

    public List<EvidenceItem> getEvidence(UUID id) {
        return getInvestigation(id).map(InvestigationResult::evidenceItems).orElse(Collections.emptyList());
    }

    public List<com.traceback.core.domain.Hypothesis> getHypotheses(UUID id) {
        return getInvestigation(id).map(InvestigationResult::hypotheses).orElse(Collections.emptyList());
    }
}
