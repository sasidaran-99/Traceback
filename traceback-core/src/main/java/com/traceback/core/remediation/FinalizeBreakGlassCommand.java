package com.traceback.core.remediation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for a second administrator to finalize a break-glass request.
 */
public record FinalizeBreakGlassCommand(
        UUID operationId,
        UUID requestId,
        String executionNotes
) {
    public FinalizeBreakGlassCommand {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(requestId, "requestId cannot be null");
    }
}
