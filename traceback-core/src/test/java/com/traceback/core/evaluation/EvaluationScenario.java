package com.traceback.core.evaluation;

import com.traceback.core.git.CommitEvidence;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;

import java.util.Collections;
import java.util.List;

/**
 * Definition of a reproducible evaluation scenario for benchmarking Traceback.
 * Ground-truth labels are isolated here and NEVER exposed as input to the correlation engine.
 */
public record EvaluationScenario(
        String scenarioId,
        String title,
        String serviceName,
        String environment,
        List<CommitEvidence> inputCommits,
        LogEvidence inputLogEvidence,
        ParsedException inputParsedException,
        String expectedRootCauseSha,
        boolean expectAbstention,
        List<String> misleadingShas
) {
    public EvaluationScenario {
        if (inputCommits == null) inputCommits = Collections.emptyList();
        if (misleadingShas == null) misleadingShas = Collections.emptyList();
    }
}
