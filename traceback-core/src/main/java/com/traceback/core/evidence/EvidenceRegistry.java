package com.traceback.core.evidence;

import com.traceback.core.domain.RelationshipCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry contract for registering, querying, and auditing evidence claims across the system.
 */
public interface EvidenceRegistry {

    /**
     * Registers an evidence claim after validation.
     *
     * @param claim the claim to register
     * @return the registered claim
     */
    EvidenceClaim registerClaim(EvidenceClaim claim);

    /**
     * Finds a claim by its unique identifier.
     *
     * @param claimId unique identifier
     * @return optional claim
     */
    Optional<EvidenceClaim> findById(UUID claimId);

    /**
     * Retrieves all claims where the specified event is the source.
     *
     * @param sourceEventId event ID
     * @return list of matching claims
     */
    List<EvidenceClaim> findBySourceEvent(UUID sourceEventId);

    /**
     * Retrieves all claims where the specified event is the target.
     *
     * @param targetEventId event ID
     * @return list of matching claims
     */
    List<EvidenceClaim> findByTargetEvent(UUID targetEventId);

    /**
     * Retrieves all claims of a specific epistemological category.
     *
     * @param category EXPLICIT, INFERRED, or TEMPORAL
     * @return list of matching claims
     */
    List<EvidenceClaim> findByCategory(RelationshipCategory category);
}
