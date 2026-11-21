package com.traceback.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing a durable agent report.
 */
@Entity
@Table(name = "investigation_agent_reports")
public class AgentReportEntity {

    @Id
    @Column(name = "report_id", nullable = false, length = 64)
    private String reportId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "verdict", nullable = false, length = 64)
    private String verdict;

    @Column(name = "executive_summary", columnDefinition = "TEXT")
    private String executiveSummary;

    @Column(name = "ai_reasoning_summary", columnDefinition = "TEXT")
    private String aiReasoningSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confidence_breakdown", columnDefinition = "jsonb")
    private String confidenceBreakdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validated_claims", columnDefinition = "jsonb")
    private String validatedClaims;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_verification_steps", columnDefinition = "jsonb")
    private String recommendedVerificationSteps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "remaining_uncertainties", columnDefinition = "jsonb")
    private String remainingUncertainties;

    @Column(name = "primary_suspected_root_cause", columnDefinition = "TEXT")
    private String primarySuspectedRootCause;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_evidence_ids", columnDefinition = "jsonb")
    private String supportingEvidenceIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contradictory_evidence_ids", columnDefinition = "jsonb")
    private String contradictoryEvidenceIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AgentReportEntity() {
    }

    public AgentReportEntity(String reportId, UUID investigationId, String verdict, String executiveSummary,
                             String aiReasoningSummary, String confidenceBreakdown, String validatedClaims,
                             String recommendedVerificationSteps, String remainingUncertainties) {
        this.reportId = reportId;
        this.investigationId = investigationId;
        this.verdict = verdict;
        this.executiveSummary = executiveSummary;
        this.aiReasoningSummary = aiReasoningSummary;
        this.confidenceBreakdown = confidenceBreakdown;
        this.validatedClaims = validatedClaims;
        this.recommendedVerificationSteps = recommendedVerificationSteps;
        this.remainingUncertainties = remainingUncertainties;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(UUID investigationId) {
        this.investigationId = investigationId;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getExecutiveSummary() {
        return executiveSummary;
    }

    public void setExecutiveSummary(String executiveSummary) {
        this.executiveSummary = executiveSummary;
    }

    public String getAiReasoningSummary() {
        return aiReasoningSummary;
    }

    public void setAiReasoningSummary(String aiReasoningSummary) {
        this.aiReasoningSummary = aiReasoningSummary;
    }

    public String getConfidenceBreakdown() {
        return confidenceBreakdown;
    }

    public void setConfidenceBreakdown(String confidenceBreakdown) {
        this.confidenceBreakdown = confidenceBreakdown;
    }

    public String getValidatedClaims() {
        return validatedClaims;
    }

    public void setValidatedClaims(String validatedClaims) {
        this.validatedClaims = validatedClaims;
    }

    public String getRecommendedVerificationSteps() {
        return recommendedVerificationSteps;
    }

    public void setRecommendedVerificationSteps(String recommendedVerificationSteps) {
        this.recommendedVerificationSteps = recommendedVerificationSteps;
    }

    public String getRemainingUncertainties() {
        return remainingUncertainties;
    }

    public void setRemainingUncertainties(String remainingUncertainties) {
        this.remainingUncertainties = remainingUncertainties;
    }

    public String getPrimarySuspectedRootCause() {
        return primarySuspectedRootCause;
    }

    public void setPrimarySuspectedRootCause(String primarySuspectedRootCause) {
        this.primarySuspectedRootCause = primarySuspectedRootCause;
    }

    public String getSupportingEvidenceIds() {
        return supportingEvidenceIds;
    }

    public void setSupportingEvidenceIds(String supportingEvidenceIds) {
        this.supportingEvidenceIds = supportingEvidenceIds;
    }

    public String getContradictoryEvidenceIds() {
        return contradictoryEvidenceIds;
    }

    public void setContradictoryEvidenceIds(String contradictoryEvidenceIds) {
        this.contradictoryEvidenceIds = contradictoryEvidenceIds;
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
        if (!(o instanceof AgentReportEntity that)) return false;
        return Objects.equals(reportId, that.reportId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportId);
    }
}
