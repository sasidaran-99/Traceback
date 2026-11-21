package com.traceback.core.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.core.domain.EvidenceItemEntity;
import com.traceback.core.domain.RelationshipCategory;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe evidence registry combining fast in-memory lookups with durable PostgreSQL persistence.
 * Supports full 64-character SHA-256 validation for new evidence and backward compatibility
 * for legacy 16-character truncated hashes.
 */
@Component
public class ConcreteEvidenceRegistry implements EvidenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConcreteEvidenceRegistry.class);

    private final Map<UUID, Map<String, EvidenceItem>> itemsByInvestigationId = new ConcurrentHashMap<>();
    private final Map<String, EvidenceItem> legacyItemsById = new ConcurrentHashMap<>();
    private final Map<UUID, EvidenceClaim> claimsById = new ConcurrentHashMap<>();

    private final InvestigationEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConcreteEvidenceRegistry(@Autowired(required = false) InvestigationEvidenceRepository evidenceRepository,
                                    @Autowired(required = false) ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public ConcreteEvidenceRegistry() {
        this(null, new ObjectMapper());
    }

    @Override
    public EvidenceItem registerItem(EvidenceItem item, UUID explicitInvestigationId) {
        if (item == null || item.evidenceId() == null) {
            throw new IllegalArgumentException("Cannot register null evidence item or item with null ID");
        }

        // Validate hash integrity upon registration
        if (!validateEvidenceHash(item)) {
            log.warn("Registering evidence item {} with unverified or mismatched integrity hash: {}",
                    item.evidenceId(), item.integrityHash());
        }

        UUID invId = explicitInvestigationId != null ? explicitInvestigationId : extractInvestigationId(item);
        EvidenceItem scopedItem = item;
        if (invId != null && (item.investigationId() == null || !invId.equals(item.investigationId()))) {
            scopedItem = item.withOwnership(invId, item.originatingInvestigationId());
        }

        if (invId != null) {
            itemsByInvestigationId.computeIfAbsent(invId, k -> new ConcurrentHashMap<>())
                    .put(scopedItem.evidenceId(), scopedItem);
        }
        legacyItemsById.put(scopedItem.evidenceId(), scopedItem);

        // Durable persistence if repository is active
        if (evidenceRepository != null && invId != null) {
            boolean isGenuineActive = scopedItem.isGenuinelyCollectedFor(invId);
            boolean isGlobal = scopedItem.isGlobal() || "GLOBAL".equalsIgnoreCase(String.valueOf(scopedItem.metadata().get("investigationId")));
            if (isGenuineActive || isGlobal) {
                try {
                    if (!evidenceRepository.existsByInvestigationIdAndEvidenceId(invId, scopedItem.evidenceId())) {
                        EvidenceItemEntity entity = toEntity(scopedItem, invId);
                        evidenceRepository.save(entity);
                        log.debug("Persisted evidence item {} to database (invId: {})", scopedItem.evidenceId(), invId);
                    }
                } catch (Exception e) {
                    log.error("Failed to persist evidence item {} to database: {}", scopedItem.evidenceId(), e.getMessage());
                }
            } else {
                log.warn("Skipping persistence of foreign-origin evidence item {} (origin: {}) for investigation {}",
                        scopedItem.evidenceId(), scopedItem.originatingInvestigationId(), invId);
            }
        }

        return scopedItem;
    }

    @Override
    public EvidenceItem registerItem(EvidenceItem item) {
        return registerItem(item, extractInvestigationId(item));
    }

    @Override
    public Optional<EvidenceItem> findItemById(String evidenceId, UUID investigationId) {
        if (evidenceId == null || investigationId == null) {
            return Optional.empty();
        }

        // 1. Check investigation-scoped in-memory cache
        Map<String, EvidenceItem> scopedMap = itemsByInvestigationId.get(investigationId);
        if (scopedMap != null) {
            EvidenceItem inMemory = scopedMap.get(evidenceId);
            if (inMemory != null) {
                validateEvidenceHash(inMemory);
                return Optional.of(inMemory);
            }
        }

        // 2. Query database scoped strictly to this investigation
        if (evidenceRepository != null) {
            Optional<EvidenceItemEntity> entityOpt = evidenceRepository.findByInvestigationIdAndEvidenceId(investigationId, evidenceId);
            if (entityOpt.isPresent()) {
                EvidenceItem item = toDomain(entityOpt.get());
                validateEvidenceHash(item);
                itemsByInvestigationId.computeIfAbsent(investigationId, k -> new ConcurrentHashMap<>())
                        .put(item.evidenceId(), item);
                return Optional.of(item);
            }
        }

        // 3. Fallback: check legacy in-memory cache for unscoped or global items
        EvidenceItem legacy = legacyItemsById.get(evidenceId);
        if (legacy != null) {
            UUID legacyInvId = extractInvestigationId(legacy);
            if (legacyInvId == null || legacyInvId.equals(investigationId)) {
                validateEvidenceHash(legacy);
                return Optional.of(legacy);
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<EvidenceItem> findItemById(String evidenceId) {
        if (evidenceId == null) return Optional.empty();

        EvidenceItem legacy = legacyItemsById.get(evidenceId);
        if (legacy != null) {
            validateEvidenceHash(legacy);
            return Optional.of(legacy);
        }

        for (Map<String, EvidenceItem> scoped : itemsByInvestigationId.values()) {
            EvidenceItem inMemory = scoped.get(evidenceId);
            if (inMemory != null) {
                validateEvidenceHash(inMemory);
                return Optional.of(inMemory);
            }
        }

        if (evidenceRepository != null) {
            Optional<EvidenceItemEntity> entityOpt = evidenceRepository.findFirstByEvidenceId(evidenceId);
            if (entityOpt.isPresent()) {
                EvidenceItem item = toDomain(entityOpt.get());
                validateEvidenceHash(item);
                legacyItemsById.put(item.evidenceId(), item);
                return Optional.of(item);
            }
        }

        return Optional.empty();
    }

    @Override
    public List<EvidenceItem> findAllItems() {
        if (evidenceRepository != null) {
            try {
                List<EvidenceItemEntity> allEntities = evidenceRepository.findAll();
                for (EvidenceItemEntity entity : allEntities) {
                    EvidenceItem item = toDomain(entity);
                    if (entity.getInvestigationId() != null) {
                        itemsByInvestigationId.computeIfAbsent(entity.getInvestigationId(), k -> new ConcurrentHashMap<>())
                                .put(item.evidenceId(), item);
                    }
                    legacyItemsById.put(item.evidenceId(), item);
                }
            } catch (Exception e) {
                log.warn("Could not query all evidence from database, falling back to memory: {}", e.getMessage());
            }
        }
        Map<String, EvidenceItem> combined = new ConcurrentHashMap<>(legacyItemsById);
        for (Map<String, EvidenceItem> scoped : itemsByInvestigationId.values()) {
            combined.putAll(scoped);
        }
        return new ArrayList<>(combined.values());
    }

    @Override
    public List<EvidenceItem> findItemsByType(EvidenceType type) {
        return findAllItems().stream()
                .filter(item -> item.evidenceType() == type)
                .toList();
    }

    @Override
    public List<EvidenceItem> findItemsBySource(String source) {
        return findAllItems().stream()
                .filter(item -> item.source().equalsIgnoreCase(source))
                .toList();
    }

    @Override
    public List<EvidenceItem> findByInvestigationId(UUID investigationId) {
        if (investigationId == null) {
            return Collections.emptyList();
        }

        Map<String, EvidenceItem> merged = new java.util.LinkedHashMap<>();

        // 1. Query database scoped strictly to this investigation
        if (evidenceRepository != null) {
            try {
                List<EvidenceItemEntity> entities = evidenceRepository.findByInvestigationId(investigationId);
                for (EvidenceItemEntity entity : entities) {
                    EvidenceItem item = toDomain(entity);
                    validateEvidenceHash(item);
                    merged.put(item.evidenceId(), item);
                }
            } catch (Exception e) {
                log.warn("Database lookup for investigation {} evidence failed: {}", investigationId, e.getMessage());
            }
        }

        // 2. Overlay with in-memory scoped items
        Map<String, EvidenceItem> scoped = itemsByInvestigationId.get(investigationId);
        if (scoped != null && !scoped.isEmpty()) {
            for (Map.Entry<String, EvidenceItem> entry : scoped.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        if (!merged.isEmpty()) {
            return new ArrayList<>(merged.values());
        }

        return legacyItemsById.values().stream()
                .filter(item -> {
                    UUID id = extractInvestigationId(item);
                    return investigationId.equals(id);
                })
                .toList();
    }

    /**
     * Validates an evidence item's hash against the raw payload.
     * Supports both 64-character full SHA-256 and legacy 16-character truncated hashes.
     */
    public boolean validateEvidenceHash(EvidenceItem item) {
        if (item == null || item.integrityHash() == null) {
            return false;
        }

        String rawPayload = item.evidenceId() + "|" + item.source() + "|" + item.originalReference() + "|" + item.summary();
        String fullSha256 = EvidenceItem.computeFullSha256(rawPayload);

        if (item.integrityHash().length() == 64) {
            return fullSha256.equalsIgnoreCase(item.integrityHash());
        } else if (item.integrityHash().length() == 16) {
            String legacyExpected = fullSha256.substring(0, 16);
            return legacyExpected.equalsIgnoreCase(item.integrityHash());
        }

        return false;
    }

    @Override
    public void clear() {
        itemsByInvestigationId.clear();
        legacyItemsById.clear();
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

    private UUID extractInvestigationId(EvidenceItem item) {
        if (item.metadata() != null && item.metadata().containsKey("investigationId")) {
            Object val = item.metadata().get("investigationId");
            if (val instanceof UUID u) return u;
            if (val instanceof String s && !s.isBlank()) {
                try {
                    return UUID.fromString(s);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public EvidenceItemEntity toEntity(EvidenceItem item, UUID investigationId) {
        String payloadJson = null;
        UUID authoritativeInvId = investigationId != null ? investigationId : item.investigationId();

        Map<String, Object> metadataToPersist = new java.util.LinkedHashMap<>();
        if (item.metadata() != null) {
            metadataToPersist.putAll(item.metadata());
        }

        // Authoritative ownership & provenance synchronization
        if (authoritativeInvId != null) {
            String targetInvStr = authoritativeInvId.toString();
            Object existingInv = metadataToPersist.get("investigationId");
            // If item already had an originatingInvestigationId, preserve it unconditionally (never overwrite historical provenance)
            if (!metadataToPersist.containsKey("originatingInvestigationId")) {
                if (item.isGlobal() || (existingInv != null && "GLOBAL".equalsIgnoreCase(existingInv.toString()))) {
                    metadataToPersist.put("originatingInvestigationId", "GLOBAL");
                } else if (existingInv != null && !existingInv.toString().equalsIgnoreCase(targetInvStr)) {
                    metadataToPersist.put("originatingInvestigationId", existingInv.toString());
                } else {
                    metadataToPersist.put("originatingInvestigationId", targetInvStr);
                }
            }
            metadataToPersist.put("investigationId", targetInvStr);
        }

        try {
            payloadJson = objectMapper.writeValueAsString(metadataToPersist);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata for {}: {}", item.evidenceId(), e.getMessage());
        }
        return new EvidenceItemEntity(
                item.evidenceId(),
                authoritativeInvId,
                item.evidenceType().name(),
                item.source(),
                item.originalReference(),
                item.summary(),
                payloadJson,
                item.integrityHash()
        );
    }

    public EvidenceItem toDomain(EvidenceItemEntity entity) {
        EvidenceType type;
        try {
            type = EvidenceType.valueOf(entity.getEvidenceType());
        } catch (Exception e) {
            type = EvidenceType.COMMIT;
        }

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        String rawPayloadInvId = null;
        if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
            try {
                Map<String, Object> deserialized = objectMapper.readValue(entity.getPayload(), new TypeReference<Map<String, Object>>() {});
                if (deserialized != null) {
                    metadata.putAll(deserialized);
                    if (deserialized.get("investigationId") != null) {
                        rawPayloadInvId = deserialized.get("investigationId").toString();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize payload for {}: {}", entity.getEvidenceId(), e.getMessage());
            }
        }

        // Database column entity.getInvestigationId() is ALWAYS the authoritative ownership field
        if (entity.getInvestigationId() != null) {
            String dbInvStr = entity.getInvestigationId().toString();
            if (rawPayloadInvId != null && !rawPayloadInvId.equalsIgnoreCase(dbInvStr) && !"GLOBAL".equalsIgnoreCase(rawPayloadInvId)) {
                if (!metadata.containsKey("originatingInvestigationId")) {
                    metadata.put("originatingInvestigationId", rawPayloadInvId);
                }
            } else if (!metadata.containsKey("originatingInvestigationId")) {
                metadata.put("originatingInvestigationId", dbInvStr);
            }
            metadata.put("investigationId", dbInvStr);
        }

        return new EvidenceItem(
                entity.getEvidenceId(),
                type,
                entity.getSource(),
                entity.getOriginalReference(),
                entity.getCreatedAt(),
                entity.getSummary(),
                metadata,
                entity.getIntegrityHash()
        );
    }

    /**
     * Canonical evidence ID generator for configuration diff evidence.
     * Guarantees identical deterministic identifier across TimelineBuilder,
     * CorrelationEngine, and ClaimValidator.
     */
    public static String configEvidenceId(String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return "EVD-CFG-UNKNOWN";
        }
        String clean = commitSha.trim();
        String shortSha = clean.length() > 7 ? clean.substring(0, 7) : clean;
        return "EVD-CFG-" + shortSha;
    }
}
