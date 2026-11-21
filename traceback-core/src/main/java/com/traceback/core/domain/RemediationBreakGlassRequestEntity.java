package com.traceback.core.domain;

import com.traceback.core.remediation.RemediationBreakGlassDecision;
import com.traceback.core.remediation.RemediationBreakGlassStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable persistence entity representing an administrative dual-custody break-glass recovery request.
 * All snapshot proposal fields are immutable after insertion.
 */
@Entity
@Table(name = "remediation_break_glass_requests")
public class RemediationBreakGlassRequestEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "repository_path", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String repositoryPath;

    @Column(name = "target_ref", nullable = false, length = 255, updatable = false)
    private String targetRef;

    @Column(name = "expected_old_sha", nullable = false, length = 64, updatable = false)
    private String expectedOldSha;

    @Column(name = "proposed_new_sha", nullable = false, length = 64, updatable = false)
    private String proposedNewSha;

    @Column(name = "snapshot_git_sha", nullable = false, length = 64, updatable = false)
    private String snapshotGitSha;

    @Column(name = "operation_fencing_token", nullable = false, updatable = false)
    private Long operationFencingToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_decision", nullable = false, length = 32, updatable = false)
    private RemediationBreakGlassDecision requestedDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RemediationBreakGlassStatus status;

    @Column(name = "rationale", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String rationale;

    @Column(name = "evidence_digest", length = 64, updatable = false)
    private String evidenceDigest;

    @Column(name = "evidence_bundle_ref", columnDefinition = "TEXT", updatable = false)
    private String evidenceBundleRef;

    @Column(name = "evidence_bundle_version", length = 64, updatable = false)
    private String evidenceBundleVersion;

    @Column(name = "initiator_issuer", nullable = false, length = 255, updatable = false)
    private String initiatorIssuer;

    @Column(name = "initiator_subject", nullable = false, length = 255, updatable = false)
    private String initiatorSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "approver_issuer", length = 255)
    private String approverIssuer;

    @Column(name = "approver_subject", length = 255)
    private String approverSubject;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "execution_notes", columnDefinition = "TEXT")
    private String executionNotes;

    @Column(name = "cancelled_by_issuer", length = 255)
    private String cancelledByIssuer;

    @Column(name = "cancelled_by_subject", length = 255)
    private String cancelledBySubject;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    public RemediationBreakGlassRequestEntity() {
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

    public String getSnapshotGitSha() {
        return snapshotGitSha;
    }

    public void setSnapshotGitSha(String snapshotGitSha) {
        this.snapshotGitSha = snapshotGitSha;
    }

    public Long getOperationFencingToken() {
        return operationFencingToken;
    }

    public void setOperationFencingToken(Long operationFencingToken) {
        this.operationFencingToken = operationFencingToken;
    }

    public RemediationBreakGlassDecision getRequestedDecision() {
        return requestedDecision;
    }

    public void setRequestedDecision(RemediationBreakGlassDecision requestedDecision) {
        this.requestedDecision = requestedDecision;
    }

    public RemediationBreakGlassStatus getStatus() {
        return status;
    }

    public void setStatus(RemediationBreakGlassStatus status) {
        this.status = status;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getEvidenceDigest() {
        return evidenceDigest;
    }

    public void setEvidenceDigest(String evidenceDigest) {
        this.evidenceDigest = evidenceDigest;
    }

    public String getEvidenceBundleRef() {
        return evidenceBundleRef;
    }

    public void setEvidenceBundleRef(String evidenceBundleRef) {
        this.evidenceBundleRef = evidenceBundleRef;
    }

    public String getEvidenceBundleVersion() {
        return evidenceBundleVersion;
    }

    public void setEvidenceBundleVersion(String evidenceBundleVersion) {
        this.evidenceBundleVersion = evidenceBundleVersion;
    }

    public String getInitiatorIssuer() {
        return initiatorIssuer;
    }

    public void setInitiatorIssuer(String initiatorIssuer) {
        this.initiatorIssuer = initiatorIssuer;
    }

    public String getInitiatorSubject() {
        return initiatorSubject;
    }

    public void setInitiatorSubject(String initiatorSubject) {
        this.initiatorSubject = initiatorSubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getApproverIssuer() {
        return approverIssuer;
    }

    public void setApproverIssuer(String approverIssuer) {
        this.approverIssuer = approverIssuer;
    }

    public String getApproverSubject() {
        return approverSubject;
    }

    public void setApproverSubject(String approverSubject) {
        this.approverSubject = approverSubject;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getExecutionNotes() {
        return executionNotes;
    }

    public void setExecutionNotes(String executionNotes) {
        this.executionNotes = executionNotes;
    }

    public String getCancelledByIssuer() {
        return cancelledByIssuer;
    }

    public void setCancelledByIssuer(String cancelledByIssuer) {
        this.cancelledByIssuer = cancelledByIssuer;
    }

    public String getCancelledBySubject() {
        return cancelledBySubject;
    }

    public void setCancelledBySubject(String cancelledBySubject) {
        this.cancelledBySubject = cancelledBySubject;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemediationBreakGlassRequestEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
