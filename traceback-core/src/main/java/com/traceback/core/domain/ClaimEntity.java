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
 * JPA entity representing a durable investigation claim with surrogate UUID primary key
 * and multi-tenant investigation-scoped uniqueness.
 */
@Entity
@Table(
        name = "investigation_claims",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_inv_clm_investigation_claim",
                        columnNames = { "investigation_id", "claim_id" }
                )
        }
)
public class ClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false, length = 128)
    private String claimId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "statement", nullable = false, columnDefinition = "TEXT")
    private String statement;

    @Column(name = "claim_type", nullable = false, length = 64)
    private String claimType;

    @Column(name = "validation_status", nullable = false, length = 32)
    private String validationStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_evidence_ids", columnDefinition = "jsonb")
    private String supportingEvidenceIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contradicting_evidence_ids", columnDefinition = "jsonb")
    private String contradictingEvidenceIds;

    @Column(name = "validation_explanation", columnDefinition = "TEXT")
    private String validationExplanation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ClaimEntity() {
    }

    public ClaimEntity(UUID id, String claimId, UUID investigationId, String statement, String claimType,
                       String validationStatus, String supportingEvidenceIds, String contradictingEvidenceIds,
                       String validationExplanation) {
        this.id = id;
        this.claimId = claimId;
        this.investigationId = investigationId;
        this.statement = statement;
        this.claimType = claimType;
        this.validationStatus = validationStatus;
        this.supportingEvidenceIds = supportingEvidenceIds;
        this.contradictingEvidenceIds = contradictingEvidenceIds;
        this.validationExplanation = validationExplanation;
    }

    public ClaimEntity(String claimId, UUID investigationId, String statement, String claimType,
                       String validationStatus, String supportingEvidenceIds, String contradictingEvidenceIds,
                       String validationExplanation) {
        this(null, claimId, investigationId, statement, claimType, validationStatus, supportingEvidenceIds, contradictingEvidenceIds, validationExplanation);
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

    public String getClaimId() {
        return claimId;
    }

    public void setClaimId(String claimId) {
        this.claimId = claimId;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(UUID investigationId) {
        this.investigationId = investigationId;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getClaimType() {
        return claimType;
    }

    public void setClaimType(String claimType) {
        this.claimType = claimType;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(String validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getSupportingEvidenceIds() {
        return supportingEvidenceIds;
    }

    public void setSupportingEvidenceIds(String supportingEvidenceIds) {
        this.supportingEvidenceIds = supportingEvidenceIds;
    }

    public String getContradictingEvidenceIds() {
        return contradictingEvidenceIds;
    }

    public void setContradictingEvidenceIds(String contradictingEvidenceIds) {
        this.contradictingEvidenceIds = contradictingEvidenceIds;
    }

    public String getValidationExplanation() {
        return validationExplanation;
    }

    public void setValidationExplanation(String validationExplanation) {
        this.validationExplanation = validationExplanation;
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
        if (!(o instanceof ClaimEntity that)) return false;
        if (id != null && that.id != null) return Objects.equals(id, that.id);
        return Objects.equals(investigationId, that.investigationId) && Objects.equals(claimId, that.claimId);
    }

    @Override
    public int hashCode() {
        if (id != null) return Objects.hash(id);
        return Objects.hash(investigationId, claimId);
    }
}
