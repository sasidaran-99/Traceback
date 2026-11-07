-- V1__initial_schema.sql
-- Traceback Event Store & Investigation Schema

-- 1. Normalized Events Table
CREATE TABLE events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    entity_id VARCHAR(256) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for high-speed temporal window queries and entity lookups
CREATE INDEX idx_events_service_env_time ON events (service_name, environment, timestamp DESC);
CREATE INDEX idx_events_entity_id ON events (entity_id);
CREATE INDEX idx_events_event_type ON events (event_type);
CREATE INDEX idx_events_timestamp ON events (timestamp DESC);

-- 2. Incident Aggregates Table
CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    incident_key VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    triggering_event_id UUID REFERENCES events(id) ON DELETE SET NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incidents_service_env ON incidents (service_name, environment);
CREATE INDEX idx_incidents_status ON incidents (status);
CREATE INDEX idx_incidents_started_at ON incidents (started_at DESC);

-- 3. Directed Evidence Graph Relationships Table
CREATE TABLE event_relationships (
    id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    target_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    relationship_type VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    confidence_weight DOUBLE PRECISION NOT NULL,
    explanation TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rel_source ON event_relationships (source_event_id);
CREATE INDEX idx_rel_target ON event_relationships (target_event_id);
CREATE INDEX idx_rel_category ON event_relationships (category);
CREATE INDEX idx_rel_type ON event_relationships (relationship_type);

-- 4. Candidate Root-Cause Hypotheses Table
CREATE TABLE hypotheses (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    candidate_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    rank INT NOT NULL,
    confidence_score DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    verification_plan TEXT,
    counter_evidence TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_hypotheses_incident ON hypotheses (incident_id);
CREATE INDEX idx_hypotheses_candidate ON hypotheses (candidate_event_id);
