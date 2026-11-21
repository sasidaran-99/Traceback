package com.traceback.core.security;

import java.util.Objects;

/**
 * Principal representing an authenticated actor invoking remediation operations.
 */
public record RemediationPrincipal(
        String actorId,
        RemediationActorRole role,
        String tenantScope
) {
    public RemediationPrincipal {
        Objects.requireNonNull(actorId, "actorId cannot be null");
        Objects.requireNonNull(role, "role cannot be null");
    }

    /**
     * Checks if this principal possesses sufficient role privileges.
     * ADMIN has full access.
     * OPERATOR can perform operational tasks and read tasks.
     * VIEWER can only read.
     */
    public boolean hasRole(RemediationActorRole requiredRole) {
        if (this.role == RemediationActorRole.ADMIN) {
            return true;
        }
        if (this.role == RemediationActorRole.OPERATOR) {
            return requiredRole == RemediationActorRole.OPERATOR || requiredRole == RemediationActorRole.VIEWER;
        }
        return this.role == requiredRole;
    }
}
