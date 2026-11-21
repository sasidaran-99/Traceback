package com.traceback.core.remediation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for the proposing administrator to cancel a pending break-glass request.
 */
public record CancelBreakGlassCommand(
        UUID operationId,
        UUID requestId,
        String cancellationReason
) {
    public CancelBreakGlassCommand {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(requestId, "requestId cannot be null");
        Objects.requireNonNull(cancellationReason, "cancellationReason cannot be null");
        if (cancellationReason.trim().isEmpty()) {
            throw new IllegalArgumentException("cancellationReason cannot be blank");
        }
        cancellationReason = cancellationReason.trim();
    }
}
