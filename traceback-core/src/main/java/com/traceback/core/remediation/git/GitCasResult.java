package com.traceback.core.remediation.git;

import com.traceback.core.remediation.RemediationFailureClassification;
import org.eclipse.jgit.lib.RefUpdate;

public record GitCasResult(
        boolean success,
        String previousSha,
        String resultingSha,
        RefUpdate.Result jgitResult,
        RemediationFailureClassification failureClassification,
        String failureMessage
) {
    public static GitCasResult success(String previousSha, String resultingSha, RefUpdate.Result jgitResult) {
        return new GitCasResult(true, previousSha, resultingSha, jgitResult, null, null);
    }

    public static GitCasResult failure(
            String previousSha,
            RefUpdate.Result jgitResult,
            RemediationFailureClassification classification,
            String message
    ) {
        return new GitCasResult(false, previousSha, null, jgitResult, classification, message);
    }
}
