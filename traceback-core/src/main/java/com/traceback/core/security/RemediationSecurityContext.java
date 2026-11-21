package com.traceback.core.security;

/**
 * Thread-local context storing the authenticated RemediationPrincipal for the current execution thread.
 */
public final class RemediationSecurityContext {

    private static final ThreadLocal<RemediationPrincipal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    private RemediationSecurityContext() {
    }

    public static void setPrincipal(RemediationPrincipal principal) {
        CURRENT_PRINCIPAL.set(principal);
    }

    public static RemediationPrincipal getPrincipal() {
        return CURRENT_PRINCIPAL.get();
    }

    public static RemediationPrincipal requirePrincipal() {
        RemediationPrincipal principal = CURRENT_PRINCIPAL.get();
        if (principal == null) {
            throw new RemediationAuthenticationException("Authentication required: No principal found in security context");
        }
        return principal;
    }

    public static void clear() {
        CURRENT_PRINCIPAL.remove();
    }
}
