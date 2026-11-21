package com.traceback.core.domain;

import com.traceback.core.remediation.RemediationFailureClassification;
import com.traceback.core.remediation.RemediationOperationStatus;
import com.traceback.core.remediation.RemediationOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable persistence entity representing a remediation operation proposal and execution lifecycle.
 */
@Entity
@Table(name = "remediation_operations")
public class RemediationOperationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "investigation_id")
    private UUID investigationId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "repository_path", nullable = false, columnDefinition = "TEXT")
    private String repositoryPath;

    @Column(name = "target_ref", nullable = false, length = 255)
    private String targetRef;

    @Column(name = "expected_old_sha", nullable = false, length = 64)
    private String expectedOldSha;

    @Column(name = "proposed_new_sha", nullable = false, length = 64)
    private String proposedNewSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 64)
    private RemediationOperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RemediationOperationStatus status;

    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "fencing_token", nullable = false)
    private Long fencingToken = 1L;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "initiated_by", nullable = false, length = 128)
    private String initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_classification", length = 64)
    private RemediationFailureClassification failureClassification;

    @Column(name = "failure_details", columnDefinition = "TEXT")
    private String failureDetails;

    @Column(name = "recovery_status", length = 64)
    private String recoveryStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RemediationOperationEntity() {
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (fencingToken == null) {
            fencingToken = 1L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(UUID investigationId) {
        this.investigationId = investigationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRepositoryPath() {
        return repositoryPath;
    }

    public void setRepositoryPath(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public void setTargetRef(String targetRef) {
        this.targetRef = targetRef;
    }

    public String getExpectedOldSha() {
        return expectedOldSha;
    }

    public void setExpectedOldSha(String expectedOldSha) {
        this.expectedOldSha = expectedOldSha;
    }

    public String getProposedNewSha() {
        return proposedNewSha;
    }

    public void setProposedNewSha(String proposedNewSha) {
        this.proposedNewSha = proposedNewSha;
    }

    public RemediationOperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(RemediationOperationType operationType) {
        this.operationType = operationType;
    }

    public RemediationOperationStatus getStatus() {
        return status;
    }

    public void setStatus(RemediationOperationStatus status) {
        this.status = status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(String initiatedBy) {
        this.initiatedBy = initiatedBy;
    }

    public RemediationFailureClassification getFailureClassification() {
        return failureClassification;
    }

    public void setFailureClassification(RemediationFailureClassification failureClassification) {
        this.failureClassification = failureClassification;
    }

    public String getFailureDetails() {
        return failureDetails;
    }

    public void setFailureDetails(String failureDetails) {
        this.failureDetails = failureDetails;
    }

    public String getRecoveryStatus() {
        return recoveryStatus;
    }

    public void setRecoveryStatus(String recoveryStatus) {
        this.recoveryStatus = recoveryStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemediationOperationEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
