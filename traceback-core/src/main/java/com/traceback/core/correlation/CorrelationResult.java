package com.traceback.core.correlation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.Relationship;
import com.traceback.core.validation.InvestigationClaim;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of executing the deterministic correlation engine over collected telemetry.
 */
public record CorrelationResult(
        List<Relationship> relationships,
        List<Hypothesis> rankedHypotheses,
        Map<String, List<String>> supportingEvidenceByCommitSha,
        Map<String, String> counterEvidenceByCommitSha,
        Map<String, ConfidenceBreakdown> confidenceBreakdownsByCommitSha,
        List<InvestigationClaim> claims,
        boolean evidenceSufficient
) {
    public CorrelationResult {
        if (relationships == null) {
            relationships = Collections.emptyList();
        }
        if (rankedHypotheses == null) {
            rankedHypotheses = Collections.emptyList();
        }
        if (supportingEvidenceByCommitSha == null) {
            supportingEvidenceByCommitSha = Collections.emptyMap();
        }
        if (counterEvidenceByCommitSha == null) {
            counterEvidenceByCommitSha = Collections.emptyMap();
        }
        if (confidenceBreakdownsByCommitSha == null) {
            confidenceBreakdownsByCommitSha = Collections.emptyMap();
        }
        if (claims == null) {
            claims = Collections.emptyList();
        }
    }

    public CorrelationResult(
            List<Relationship> relationships,
            List<Hypothesis> rankedHypotheses,
            Map<String, List<String>> supportingEvidenceByCommitSha,
            Map<String, String> counterEvidenceByCommitSha
    ) {
        this(relationships, rankedHypotheses, supportingEvidenceByCommitSha, counterEvidenceByCommitSha,
                Collections.emptyMap(), Collections.emptyList(), true);
    }
}
