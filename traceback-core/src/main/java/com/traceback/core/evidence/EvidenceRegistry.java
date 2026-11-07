package com.traceback.core.evidence;

import com.traceback.core.domain.RelationshipCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry contract for registering, querying, and auditing evidence items and claims across Traceback.
 */
public interface EvidenceRegistry {

    /**
     * Registers a collected evidence item with a unique evidence ID.
     */
    EvidenceItem registerItem(EvidenceItem item);

    /**
     * Finds an evidence item by its unique evidence ID.
     */
    Optional<EvidenceItem> findItemById(String evidenceId);

    /**
     * Retrieves all registered evidence items.
     */
    List<EvidenceItem> findAllItems();

    /**
     * Retrieves all evidence items of a specific functional type.
     */
    List<EvidenceItem> findItemsByType(EvidenceType type);

    /**
     * Retrieves all evidence items from a specific source (e.g. GIT, LOG, STACK_TRACE).
     */
    List<EvidenceItem> findItemsBySource(String source);

    /**
     * Clears all registered items and claims (useful for per-investigation isolation).
     */
    void clear();

    // Claim operations
    EvidenceClaim registerClaim(EvidenceClaim claim);
    Optional<EvidenceClaim> findById(UUID claimId);
    List<EvidenceClaim> findBySourceEvent(UUID sourceEventId);
    List<EvidenceClaim> findByTargetEvent(UUID targetEventId);
    List<EvidenceClaim> findByCategory(RelationshipCategory category);
}
