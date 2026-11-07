package com.traceback.core.correlation;

import com.traceback.core.domain.Hypothesis;
import com.traceback.core.domain.Relationship;

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
        Map<String, String> counterEvidenceByCommitSha
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
    }
}
