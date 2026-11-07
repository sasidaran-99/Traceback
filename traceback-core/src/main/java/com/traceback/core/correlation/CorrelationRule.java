package com.traceback.core.correlation;

import com.traceback.core.domain.Relationship;
import com.traceback.core.git.CommitEvidence;
import com.traceback.core.log.LogEvidence;
import com.traceback.core.stacktrace.ParsedException;

import java.util.List;

/**
 * Strategy interface for deterministic correlation rules.
 */
public interface CorrelationRule {

    /**
     * Unique rule identifier.
     */
    String getRuleId();

    /**
     * Evaluates correlation between candidate commits and observed failure symptoms.
     */
    List<Relationship> evaluate(List<CommitEvidence> commits,
                                LogEvidence logEvidence,
                                ParsedException parsedStack);
}
