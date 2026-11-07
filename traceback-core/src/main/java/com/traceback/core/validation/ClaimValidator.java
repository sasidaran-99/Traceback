package com.traceback.core.validation;

import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;

import java.util.UUID;

/**
 * Validates investigation claims against an active evidence registry using deterministic rules.
 */
public interface ClaimValidator {

    /**
     * Validates an individual investigation claim against the active evidence registry.
     *
     * @param claim                 the assertion to evaluate
     * @param activeInvestigationId the ID of the ongoing investigation
     * @param registry              the evidence registry containing collected evidence items
     * @return the evaluated claim with updated status and human-readable explanation
     */
    InvestigationClaim validateClaim(InvestigationClaim claim, UUID activeInvestigationId, EvidenceRegistry registry);

    /**
     * Verifies the SHA-256 cryptographic integrity checksum of an individual evidence item.
     *
     * @param item evidence item to verify
     * @return true if checksum matches the raw content hash, false if corrupted/tampered
     */
    boolean verifyEvidenceIntegrity(EvidenceItem item);
}
