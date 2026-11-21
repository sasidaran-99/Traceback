package com.traceback.core.domain;

import com.traceback.core.remediation.RemediationOperationEventType;
import com.traceback.core.remediation.RemediationOperationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only audit journal event for remediation operations.
 */
@Entity
@Table(name = "remediation_operation_events")
public class RemediationOperationEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private Long fencingToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private RemediationOperationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32, updatable = false)
    private RemediationOperationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32, updatable = false)
    private RemediationOperationStatus toStatus;

    @Column(name = "ref_name", length = 255, updatable = false)
    private String refName;

    @Column(name = "expected_old_sha", length = 64, updatable = false)
    private String expectedOldSha;

    @Column(name = "result_sha", length = 64, updatable = false)
    private String resultSha;

    @Column(name = "jgit_result", length = 64, updatable = false)
    private String jgitResult;

    @Column(name = "details", columnDefinition = "TEXT", updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RemediationOperationEventEntity() {
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public void setOperationId(UUID operationId) {
        this.operationId = operationId;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public RemediationOperationEventType getEventType() {
        return eventType;
    }

    public void setEventType(RemediationOperationEventType eventType) {
        this.eventType = eventType;
    }

    public RemediationOperationStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(RemediationOperationStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public RemediationOperationStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(RemediationOperationStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getRefName() {
        return refName;
    }

    public void setRefName(String refName) {
        this.refName = refName;
    }

    public String getExpectedOldSha() {
        return expectedOldSha;
    }

    public void setExpectedOldSha(String expectedOldSha) {
        this.expectedOldSha = expectedOldSha;
    }

    public String getResultSha() {
        return resultSha;
    }

    public void setResultSha(String resultSha) {
        this.resultSha = resultSha;
    }

    public String getJgitResult() {
        return jgitResult;
    }

    public void setJgitResult(String jgitResult) {
        this.jgitResult = jgitResult;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
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
        if (!(o instanceof RemediationOperationEventEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
