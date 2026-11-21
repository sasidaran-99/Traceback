package com.traceback.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Coordination lock entity for target Git refs, supporting exclusive freezes and promotion shares.
 */
@Entity
@Table(name = "remediation_target_ref_locks")
public class RemediationTargetRefLockEntity {

    @Id
    @Column(name = "ref_name", nullable = false, length = 255)
    private String refName;

    @Column(name = "is_frozen", nullable = false)
    private Boolean isFrozen = false;

    @Column(name = "frozen_reason", columnDefinition = "TEXT")
    private String frozenReason;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "current_fencing_token", nullable = false)
    private Long currentFencingToken = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RemediationTargetRefLockEntity() {
    }

    public RemediationTargetRefLockEntity(String refName) {
        this.refName = refName;
        this.isFrozen = false;
        this.currentFencingToken = 1L;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (isFrozen == null) {
            isFrozen = false;
        }
        if (currentFencingToken == null) {
            currentFencingToken = 1L;
        }
    }

    // Getters and Setters

    public String getRefName() {
        return refName;
    }

    public void setRefName(String refName) {
        this.refName = refName;
    }

    public Boolean getIsFrozen() {
        return isFrozen;
    }

    public void setIsFrozen(Boolean isFrozen) {
        this.isFrozen = isFrozen;
    }

    public String getFrozenReason() {
        return frozenReason;
    }

    public void setFrozenReason(String frozenReason) {
        this.frozenReason = frozenReason;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(Instant frozenAt) {
        this.frozenAt = frozenAt;
    }

    public Long getCurrentFencingToken() {
        return currentFencingToken;
    }

    public void setCurrentFencingToken(Long currentFencingToken) {
        this.currentFencingToken = currentFencingToken;
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
        if (!(o instanceof RemediationTargetRefLockEntity that)) return false;
        return Objects.equals(refName, that.refName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(refName);
    }
}
