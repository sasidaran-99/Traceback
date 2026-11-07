package com.traceback.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Directed relationship edge in the evidence graph connecting a source event to a target event.
 */
@Entity
@Table(name = "event_relationships")
public class Relationship {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_event_id", nullable = false)
    private Event sourceEvent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_event_id", nullable = false)
    private Event targetEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 64)
    private RelationshipType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private RelationshipCategory category;

    @Column(name = "confidence_weight", nullable = false)
    private double confidenceWeight;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Relationship() {
    }

    public Relationship(UUID id, Event sourceEvent, Event targetEvent, RelationshipType type,
                        Double confidenceWeight, String explanation, String metadata) {
        this.id = id != null ? id : UUID.randomUUID();
        this.sourceEvent = sourceEvent;
        this.targetEvent = targetEvent;
        this.type = type;
        this.category = type != null ? type.getCategory() : RelationshipCategory.TEMPORAL;
        this.confidenceWeight = confidenceWeight != null ? confidenceWeight : (type != null ? type.getDefaultConfidence() : 0.0);
        validateConfidence(this.confidenceWeight);
        this.explanation = explanation != null ? explanation : (type != null ? type.getDescription() : "");
        this.metadata = metadata;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.type != null && this.category == null) {
            this.category = this.type.getCategory();
        }
        validateConfidence(this.confidenceWeight);
    }

    public static void validateConfidence(double weight) {
        if (weight < 0.0 || weight > 1.0) {
            throw new IllegalArgumentException("Confidence weight must be between 0.0 and 1.0, got: " + weight);
        }
    }

    public boolean isExplicit() {
        return this.category == RelationshipCategory.EXPLICIT;
    }

    public boolean isInferred() {
        return this.category == RelationshipCategory.INFERRED;
    }

    public boolean isTemporal() {
        return this.category == RelationshipCategory.TEMPORAL;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Event getSourceEvent() {
        return sourceEvent;
    }

    public void setSourceEvent(Event sourceEvent) {
        this.sourceEvent = sourceEvent;
    }

    public Event getTargetEvent() {
        return targetEvent;
    }

    public void setTargetEvent(Event targetEvent) {
        this.targetEvent = targetEvent;
    }

    public RelationshipType getType() {
        return type;
    }

    public void setType(RelationshipType type) {
        this.type = type;
        if (type != null) {
            this.category = type.getCategory();
        }
    }

    public RelationshipCategory getCategory() {
        return category;
    }

    public void setCategory(RelationshipCategory category) {
        this.category = category;
    }

    public double getConfidenceWeight() {
        return confidenceWeight;
    }

    public void setConfidenceWeight(double confidenceWeight) {
        validateConfidence(confidenceWeight);
        this.confidenceWeight = confidenceWeight;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
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
        if (!(o instanceof Relationship that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Relationship{" +
                "id=" + id +
                ", type=" + type +
                ", category=" + category +
                ", confidenceWeight=" + confidenceWeight +
                '}';
    }
}
