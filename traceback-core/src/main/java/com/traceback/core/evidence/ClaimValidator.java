package com.traceback.core.evidence;

/**
 * Validates the semantic, structural, and confidence integrity of evidence claims.
 */
public interface ClaimValidator {

    /**
     * Evaluates whether an evidence claim satisfies structural and empirical validity criteria.
     *
     * @param claim the claim to validate
     * @return true if valid, false if invalid or unsupported
     */
    boolean validate(EvidenceClaim claim);

    /**
     * Provides a diagnostic explanation when a claim fails validation.
     *
     * @param claim the claim that was evaluated
     * @return validation diagnostic message
     */
    String getValidationMessage(EvidenceClaim claim);
}
