package com.traceback.core.investigation;

import com.traceback.core.correlation.ConfidenceBreakdown;
import com.traceback.core.correlation.CorrelationResult;
import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.Relationship;
import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;
import com.traceback.core.timeline.TimelineEvent;
import com.traceback.core.validation.InvestigationClaim;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates the complete findings, evidence, and validated claims of an investigation.
 */
public record InvestigationResult(
        UUID investigationId,
        String serviceName,
        String environment,
        Instant startedAt,
        Instant completedAt,
        String status,
        int commitsAnalyzed,
        int errorsDetected,
        ParsedException primaryException,
        List<TimelineEvent> timeline,
        List<EvidenceItem> evidenceItems,
        List<Relationship> relationships,
        List<Hypothesis> hypotheses,
        Map<String, List<String>> evidenceByCommitSha,
        Map<String, String> counterEvidenceByCommitSha,
        Map<String, ConfidenceBreakdown> confidenceBreakdownsByCommitSha,
        List<InvestigationClaim> claims,
        boolean evidenceSufficient
) {
    public InvestigationResult {
        if (timeline == null) timeline = Collections.emptyList();
        if (evidenceItems == null) evidenceItems = Collections.emptyList();
        if (relationships == null) relationships = Collections.emptyList();
        if (hypotheses == null) hypotheses = Collections.emptyList();
        if (evidenceByCommitSha == null) evidenceByCommitSha = Collections.emptyMap();
        if (counterEvidenceByCommitSha == null) counterEvidenceByCommitSha = Collections.emptyMap();
        if (confidenceBreakdownsByCommitSha == null) confidenceBreakdownsByCommitSha = Collections.emptyMap();
        if (claims == null) claims = Collections.emptyList();
    }

    public InvestigationResult(
            UUID investigationId,
            String serviceName,
            String environment,
            Instant startedAt,
            Instant completedAt,
            String status,
            int commitsAnalyzed,
            int errorsDetected,
            ParsedException primaryException,
            List<TimelineEvent> timeline,
            List<EvidenceItem> evidenceItems,
            List<Relationship> relationships,
            List<Hypothesis> hypotheses,
            Map<String, List<String>> evidenceByCommitSha,
            Map<String, String> counterEvidenceByCommitSha
    ) {
        this(investigationId, serviceName, environment, startedAt, completedAt, status,
                commitsAnalyzed, errorsDetected, primaryException, timeline, evidenceItems,
                relationships, hypotheses, evidenceByCommitSha, counterEvidenceByCommitSha,
                Collections.emptyMap(), Collections.emptyList(), true);
    }

    public Hypothesis getPrimaryHypothesis() {
        return hypotheses.isEmpty() ? null : hypotheses.get(0);
    }
}
