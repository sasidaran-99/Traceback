package com.traceback.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing a durable evidence item with surrogate UUID primary key
 * and multi-tenant investigation-scoped uniqueness.
 */
@Entity
@Table(
        name = "investigation_evidence",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_inv_evd_investigation_evidence",
                        columnNames = { "investigation_id", "evidence_id" }
                )
        }
)
public class EvidenceItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evidence_id", nullable = false, length = 128)
    private String evidenceId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "evidence_type", nullable = false, length = 64)
    private String evidenceType;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "original_reference", columnDefinition = "TEXT")
    private String originalReference;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Column(name = "integrity_hash", nullable = false, length = 128)
    private String integrityHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EvidenceItemEntity() {
    }

    public EvidenceItemEntity(UUID id, String evidenceId, UUID investigationId, String evidenceType, String source,
                              String originalReference, String summary, String payload, String integrityHash) {
        this.id = id;
        this.evidenceId = evidenceId;
        this.investigationId = investigationId;
        this.evidenceType = evidenceType;
        this.source = source;
        this.originalReference = originalReference;
        this.summary = summary;
        this.payload = payload;
        this.integrityHash = integrityHash;
    }

    public EvidenceItemEntity(String evidenceId, UUID investigationId, String evidenceType, String source,
                              String originalReference, String summary, String payload, String integrityHash) {
        this(null, evidenceId, investigationId, evidenceType, source, originalReference, summary, payload, integrityHash);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(UUID investigationId) {
        this.investigationId = investigationId;
    }

    public String getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(String evidenceType) {
        this.evidenceType = evidenceType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getOriginalReference() {
        return originalReference;
    }

    public void setOriginalReference(String originalReference) {
        this.originalReference = originalReference;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getIntegrityHash() {
        return integrityHash;
    }

    public void setIntegrityHash(String integrityHash) {
        this.integrityHash = integrityHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvidenceItemEntity that)) return false;
        if (id != null && that.id != null) return Objects.equals(id, that.id);
        return Objects.equals(investigationId, that.investigationId) && Objects.equals(evidenceId, that.evidenceId);
    }

    @Override
    public int hashCode() {
        if (id != null) return Objects.hash(id);
        return Objects.hash(investigationId, evidenceId);
    }
}
