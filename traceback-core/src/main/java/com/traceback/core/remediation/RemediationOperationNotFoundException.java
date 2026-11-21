package com.traceback.core.remediation;

import java.util.UUID;

public class RemediationOperationNotFoundException extends RuntimeException {

    private final UUID operationId;

    public RemediationOperationNotFoundException(UUID operationId) {
        super(String.format("Remediation operation '%s' not found", operationId));
        this.operationId = operationId;
    }

    public UUID getOperationId() {
        return operationId;
    }
}
