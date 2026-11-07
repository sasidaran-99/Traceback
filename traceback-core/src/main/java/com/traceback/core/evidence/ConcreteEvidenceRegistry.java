package com.traceback.core.evidence;

import com.traceback.core.domain.RelationshipCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe evidence registry for fast lookup and validation.
 */
@Component
public class ConcreteEvidenceRegistry implements EvidenceRegistry {

    private final Map<String, EvidenceItem> itemsById = new ConcurrentHashMap<>();
    private final Map<UUID, EvidenceClaim> claimsById = new ConcurrentHashMap<>();

    @Override
    public EvidenceItem registerItem(EvidenceItem item) {
        if (item == null || item.evidenceId() == null) {
            throw new IllegalArgumentException("Cannot register null evidence item or item with null ID");
        }
        itemsById.put(item.evidenceId(), item);
        return item;
    }

    @Override
    public Optional<EvidenceItem> findItemById(String evidenceId) {
        if (evidenceId == null) return Optional.empty();
        return Optional.ofNullable(itemsById.get(evidenceId));
    }

    @Override
    public List<EvidenceItem> findAllItems() {
        return new ArrayList<>(itemsById.values());
    }

    @Override
    public List<EvidenceItem> findItemsByType(EvidenceType type) {
        return itemsById.values().stream()
                .filter(item -> item.evidenceType() == type)
                .toList();
    }

    @Override
    public List<EvidenceItem> findItemsBySource(String source) {
        return itemsById.values().stream()
                .filter(item -> item.source().equalsIgnoreCase(source))
                .toList();
    }

    @Override
    public void clear() {
        itemsById.clear();
        claimsById.clear();
    }

    @Override
    public EvidenceClaim registerClaim(EvidenceClaim claim) {
        if (claim == null || claim.claimId() == null) {
            throw new IllegalArgumentException("Cannot register null claim");
        }
        claimsById.put(claim.claimId(), claim);
        return claim;
    }

    @Override
    public Optional<EvidenceClaim> findById(UUID claimId) {
        if (claimId == null) return Optional.empty();
        return Optional.ofNullable(claimsById.get(claimId));
    }

    @Override
    public List<EvidenceClaim> findBySourceEvent(UUID sourceEventId) {
        return claimsById.values().stream()
                .filter(c -> c.sourceEventId().equals(sourceEventId))
                .toList();
    }

    @Override
    public List<EvidenceClaim> findByTargetEvent(UUID targetEventId) {
        return claimsById.values().stream()
                .filter(c -> c.targetEventId().equals(targetEventId))
                .toList();
    }

    @Override
    public List<EvidenceClaim> findByCategory(RelationshipCategory category) {
        return claimsById.values().stream()
                .filter(c -> c.category() == category)
                .toList();
    }
}
