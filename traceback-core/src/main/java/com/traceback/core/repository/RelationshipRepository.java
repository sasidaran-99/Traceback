package com.traceback.core.repository;

import com.traceback.core.domain.Relationship;
import com.traceback.core.domain.RelationshipCategory;
import com.traceback.core.domain.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for event relationships in the evidence graph.
 */
@Repository
public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {

    /**
     * Finds relationships originating from a specific event.
     */
    List<Relationship> findBySourceEventId(UUID sourceEventId);

    /**
     * Finds relationships targeting a specific event.
     */
    List<Relationship> findByTargetEventId(UUID targetEventId);

    /**
     * Finds relationships by relationship category (EXPLICIT, INFERRED, TEMPORAL).
     */
    List<Relationship> findByCategory(RelationshipCategory category);

    /**
     * Finds relationships by exact relationship type.
     */
    List<Relationship> findByType(RelationshipType type);
}
