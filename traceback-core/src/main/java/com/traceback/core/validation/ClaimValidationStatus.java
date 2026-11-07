package com.traceback.core.validation;

/**
 * Deterministic validation status of an investigation claim.
 */
public enum ClaimValidationStatus {
    /**
     * The claim is fully substantiated by registered, verified evidence items
     * with no unaddressed contradictions.
     */
    SUPPORTED,

    /**
     * The claim has valid supporting evidence, but also unaddressed or partial
     * contradictory evidence that dampens its certainty.
     */
    PARTIALLY_SUPPORTED,

    /**
     * The claim lacks supporting evidence, cites nonexistent/tampered evidence,
     * or cites evidence that does not logically support the conclusion.
     */
    UNSUPPORTED,

    /**
     * The claim is directly refuted by verifiable counter-evidence.
     */
    CONTRADICTED
}
