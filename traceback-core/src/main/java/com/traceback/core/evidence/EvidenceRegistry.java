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
     * Registers a collected evidence item scoped to a specific investigation.
     */
    default EvidenceItem registerItem(EvidenceItem item, UUID investigationId) {
        return registerItem(item);
    }

    /**
     * Finds an evidence item by its unique evidence ID and investigation ID.
     * Enforces strict multi-tenant / investigation boundary isolation.
     */
    Optional<EvidenceItem> findItemById(String evidenceId, UUID investigationId);

    /**
     * Finds an evidence item by its unique evidence ID across all scopes (legacy fallback).
     * @deprecated Use {@link #findItemById(String, UUID)} for investigation-scoped lookups.
     */
    @Deprecated(since = "9.8.4")
    Optional<EvidenceItem> findItemById(String evidenceId);

    /**
     * Retrieves all evidence items scoped strictly to an investigation.
     */
    List<EvidenceItem> findByInvestigationId(UUID investigationId);

    /**
     * Retrieves all registered evidence items.
     */
    List<EvidenceItem> findAllItems();

    /**
     * Retrieves all evidence items of a specific functional type.
     */
    List<EvidenceItem> findItemsByType(EvidenceType type);

    /**
     * Retrieves evidence items of a specific functional type scoped strictly to an investigation.
     */
    default List<EvidenceItem> findItemsByType(EvidenceType type, UUID investigationId) {
        if (investigationId == null) {
            return findItemsByType(type);
        }
        return findByInvestigationId(investigationId).stream()
                .filter(item -> item.evidenceType() == type)
                .toList();
    }

    /**
     * Retrieves all evidence items from a specific source (e.g. GIT, LOG, STACK_TRACE).
     */
    List<EvidenceItem> findItemsBySource(String source);

    /**
     * Retrieves evidence items from a specific source scoped strictly to an investigation.
     */
    default List<EvidenceItem> findItemsBySource(String source, UUID investigationId) {
        if (investigationId == null) {
            return findItemsBySource(source);
        }
        return findByInvestigationId(investigationId).stream()
                .filter(item -> item.source().equalsIgnoreCase(source))
                .toList();
    }

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
