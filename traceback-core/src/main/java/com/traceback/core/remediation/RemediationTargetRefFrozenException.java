package com.traceback.core.remediation;

public class RemediationTargetRefFrozenException extends RuntimeException {

    private final String refName;
    private final String reason;

    public RemediationTargetRefFrozenException(String refName, String reason) {
        super(String.format("Target Git ref '%s' is frozen and cannot accept mutations. Reason: %s", refName, reason));
        this.refName = refName;
        this.reason = reason;
    }

    public String getRefName() {
        return refName;
    }

    public String getReason() {
        return reason;
    }
}
