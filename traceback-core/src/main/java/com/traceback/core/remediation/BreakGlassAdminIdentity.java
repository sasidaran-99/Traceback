package com.traceback.core.remediation;

import java.util.Objects;

/**
 * Verified administrative identity representing the (issuer, subject) identity pair derived from a validated JWT.
 */
public record BreakGlassAdminIdentity(
        String issuer,
        String subject
) {
    public BreakGlassAdminIdentity {
        Objects.requireNonNull(issuer, "issuer cannot be null");
        Objects.requireNonNull(subject, "subject cannot be null");
        if (issuer.trim().isEmpty()) {
            throw new IllegalArgumentException("issuer cannot be blank");
        }
        if (subject.trim().isEmpty()) {
            throw new IllegalArgumentException("subject cannot be blank");
        }
        issuer = issuer.trim();
        subject = subject.trim();
    }

    /**
     * Determines whether two identity pairs refer to the exact same administrative actor.
     */
    public boolean matches(String otherIssuer, String otherSubject) {
        if (otherIssuer == null || otherSubject == null) {
            return false;
        }
        return this.issuer.equals(otherIssuer.trim()) && this.subject.equals(otherSubject.trim());
    }
}
