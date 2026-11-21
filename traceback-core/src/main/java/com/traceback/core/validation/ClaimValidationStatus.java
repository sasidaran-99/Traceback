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
     * The claim lacks supporting evidence or cites evidence that does not logically support the conclusion.
     */
    UNSUPPORTED,

    /**
     * The claim is directly refuted by verifiable counter-evidence.
     */
    CONTRADICTED,

    /**
     * The claim cannot be evaluated because an evidence reference is missing, corrupt,
     * tampered, or violates investigation ownership boundaries.
     */
    VALIDATION_ERROR
}
