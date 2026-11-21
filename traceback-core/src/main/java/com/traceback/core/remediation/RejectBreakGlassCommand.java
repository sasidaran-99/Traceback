package com.traceback.core.remediation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for a second administrator to reject a break-glass request.
 */
public record RejectBreakGlassCommand(
        UUID operationId,
        UUID requestId,
        String rejectionReason
) {
    public RejectBreakGlassCommand {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(requestId, "requestId cannot be null");
        Objects.requireNonNull(rejectionReason, "rejectionReason cannot be null");
        if (rejectionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("rejectionReason cannot be blank");
        }
        rejectionReason = rejectionReason.trim();
    }
}
