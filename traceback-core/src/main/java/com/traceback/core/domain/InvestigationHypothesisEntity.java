package com.traceback.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing a durable, investigation-scoped root-cause hypothesis.
 */
@Entity
@Table(
        name = "investigation_hypotheses",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_inv_hyp_investigation_hypothesis",
                        columnNames = { "investigation_id", "hypothesis_id" }
                )
        }
)
public class InvestigationHypothesisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "hypothesis_id", nullable = false)
    private UUID hypothesisId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "verification_plan", columnDefinition = "TEXT")
    private String verificationPlan;

    @Column(name = "counter_evidence", columnDefinition = "TEXT")
    private String counterEvidence;

    @Column(name = "suspected_commit_sha", length = 64)
    private String suspectedCommitSha;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_evidence_ids", columnDefinition = "jsonb")
    private String supportingEvidenceIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contradicting_evidence_ids", columnDefinition = "jsonb")
    private String contradictingEvidenceIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_rules", columnDefinition = "jsonb")
    private String matchedRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_match_reasons", columnDefinition = "jsonb")
    private String ruleMatchReasons;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "penalty_reasons", columnDefinition = "jsonb")
    private String penaltyReasons;

    @Column(name = "ranking_explanation", columnDefinition = "TEXT")
    private String rankingExplanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confidence_breakdown", columnDefinition = "jsonb")
    private String confidenceBreakdown;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public InvestigationHypothesisEntity() {
    }

    public InvestigationHypothesisEntity(UUID id, UUID hypothesisId, UUID investigationId, int rank,
                                         double confidenceScore, String status, String summary,
                                         String verificationPlan, String counterEvidence,
                                         String suspectedCommitSha, String supportingEvidenceIds,
                                         String contradictingEvidenceIds, String matchedRules,
                                         String ruleMatchReasons, String penaltyReasons,
                                         String rankingExplanation, String confidenceBreakdown) {
        this.id = id;
        this.hypothesisId = hypothesisId != null ? hypothesisId : UUID.randomUUID();
        this.investigationId = investigationId;
        this.rank = rank;
        this.confidenceScore = confidenceScore;
        this.status = status != null ? status : "PROPOSED";
        this.summary = summary;
        this.verificationPlan = verificationPlan;
        this.counterEvidence = counterEvidence;
        this.suspectedCommitSha = suspectedCommitSha;
        this.supportingEvidenceIds = supportingEvidenceIds;
        this.contradictingEvidenceIds = contradictingEvidenceIds;
        this.matchedRules = matchedRules;
        this.ruleMatchReasons = ruleMatchReasons;
        this.penaltyReasons = penaltyReasons;
        this.rankingExplanation = rankingExplanation;
        this.confidenceBreakdown = confidenceBreakdown;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.hypothesisId == null) {
            this.hypothesisId = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getHypothesisId() {
        return hypothesisId;
    }

    public void setHypothesisId(UUID hypothesisId) {
        this.hypothesisId = hypothesisId;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(UUID investigationId) {
        this.investigationId = investigationId;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getVerificationPlan() {
        return verificationPlan;
    }

    public void setVerificationPlan(String verificationPlan) {
        this.verificationPlan = verificationPlan;
    }

    public String getCounterEvidence() {
        return counterEvidence;
    }

    public void setCounterEvidence(String counterEvidence) {
        this.counterEvidence = counterEvidence;
    }

    public String getSuspectedCommitSha() {
        return suspectedCommitSha;
    }

    public void setSuspectedCommitSha(String suspectedCommitSha) {
        this.suspectedCommitSha = suspectedCommitSha;
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

    public String getMatchedRules() {
        return matchedRules;
    }

    public void setMatchedRules(String matchedRules) {
        this.matchedRules = matchedRules;
    }

    public String getRuleMatchReasons() {
        return ruleMatchReasons;
    }

    public void setRuleMatchReasons(String ruleMatchReasons) {
        this.ruleMatchReasons = ruleMatchReasons;
    }

    public String getPenaltyReasons() {
        return penaltyReasons;
    }

    public void setPenaltyReasons(String penaltyReasons) {
        this.penaltyReasons = penaltyReasons;
    }

    public String getRankingExplanation() {
        return rankingExplanation;
    }

    public void setRankingExplanation(String rankingExplanation) {
        this.rankingExplanation = rankingExplanation;
    }

    public String getConfidenceBreakdown() {
        return confidenceBreakdown;
    }

    public void setConfidenceBreakdown(String confidenceBreakdown) {
        this.confidenceBreakdown = confidenceBreakdown;
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
        if (o == null || getClass() != o.getClass()) return false;
        InvestigationHypothesisEntity that = (InvestigationHypothesisEntity) o;
        return Objects.equals(investigationId, that.investigationId) &&
               Objects.equals(hypothesisId, that.hypothesisId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(investigationId, hypothesisId);
    }
}
