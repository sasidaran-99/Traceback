package com.traceback.core.domain;

import com.traceback.core.investigation.InvestigationMode;
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
 * JPA entity representing a durable investigation record.
 */
@Entity
@Table(name = "investigations")
public class InvestigationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 64)
    private InvestigationMode mode;

    @Column(name = "service_name", nullable = false, length = 128)
    private String serviceName;

    @Column(name = "environment", nullable = false, length = 64)
    private String environment;

    @Column(name = "repository_path", columnDefinition = "TEXT")
    private String repositoryPath;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "verdict", length = 64)
    private String verdict;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "report_id", length = 64)
    private String reportId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public InvestigationEntity() {
    }

    public InvestigationEntity(UUID id, InvestigationMode mode, String serviceName, String environment,
                               String repositoryPath, String status, String summary, String verdict,
                               Double confidenceScore, String reportId) {
        this.id = id != null ? id : UUID.randomUUID();
        this.mode = mode != null ? mode : InvestigationMode.RUNTIME_FAILURE;
        this.serviceName = serviceName;
        this.environment = environment;
        this.repositoryPath = repositoryPath;
        this.status = status != null ? status : "IN_PROGRESS";
        this.summary = summary;
        this.verdict = verdict;
        this.confidenceScore = confidenceScore;
        this.reportId = reportId;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = "IN_PROGRESS";
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

    public InvestigationMode getMode() {
        return mode;
    }

    public void setMode(InvestigationMode mode) {
        this.mode = mode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getRepositoryPath() {
        return repositoryPath;
    }

    public void setRepositoryPath(String repositoryPath) {
        this.repositoryPath = repositoryPath;
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

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
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
        if (!(o instanceof InvestigationEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
