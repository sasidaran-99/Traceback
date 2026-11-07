package com.traceback.core.repository;

import com.traceback.core.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for incident aggregates.
 */
@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * Finds incident by its human-readable key (e.g., INC-2026-001).
     */
    Optional<Incident> findByIncidentKey(String incidentKey);

    /**
     * Finds incidents by affected service and environment.
     */
    List<Incident> findByServiceNameAndEnvironment(String serviceName, String environment);

    /**
     * Finds incidents by status (e.g. OPEN, INVESTIGATING, RESOLVED).
     */
    List<Incident> findByStatus(String status);
}
