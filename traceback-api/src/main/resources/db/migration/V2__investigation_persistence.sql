-- V2__investigation_persistence.sql
-- Traceback 2.0 Investigation Persistence Schema

-- 1. Investigations Table
CREATE TABLE investigations (
    id UUID PRIMARY KEY,
    mode VARCHAR(64) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    repository_path TEXT,
    status VARCHAR(32) NOT NULL,
    summary TEXT,
    verdict VARCHAR(64),
    confidence_score DOUBLE PRECISION,
    report_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_investigations_service_env ON investigations (service_name, environment);
CREATE INDEX idx_investigations_status ON investigations (status);
CREATE INDEX idx_investigations_created_at ON investigations (created_at DESC);
CREATE INDEX idx_investigations_mode ON investigations (mode);

-- 2. Investigation Evidence Table
CREATE TABLE investigation_evidence (
    evidence_id VARCHAR(128) PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
    evidence_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    original_reference TEXT,
    summary TEXT NOT NULL,
    payload JSONB,
    integrity_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inv_evd_inv_id ON investigation_evidence (investigation_id);
CREATE INDEX idx_inv_evd_type ON investigation_evidence (evidence_type);
CREATE INDEX idx_inv_evd_source ON investigation_evidence (source);

-- 3. Investigation Claims Table
CREATE TABLE investigation_claims (
    claim_id VARCHAR(128) PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
    statement TEXT NOT NULL,
    claim_type VARCHAR(64) NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    supporting_evidence_ids JSONB,
    contradicting_evidence_ids JSONB,
    validation_explanation TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inv_claims_inv_id ON investigation_claims (investigation_id);
CREATE INDEX idx_inv_claims_status ON investigation_claims (validation_status);
CREATE INDEX idx_inv_claims_type ON investigation_claims (claim_type);

-- 4. Investigation Agent Reports Table
CREATE TABLE investigation_agent_reports (
    report_id VARCHAR(64) PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
    verdict VARCHAR(64) NOT NULL,
    executive_summary TEXT,
    ai_reasoning_summary TEXT,
    confidence_breakdown JSONB,
    validated_claims JSONB,
    recommended_verification_steps JSONB,
    remaining_uncertainties JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inv_agent_reports_inv_id ON investigation_agent_reports (investigation_id);

-- 5. Investigation Agent Steps Table
CREATE TABLE investigation_agent_steps (
    id UUID PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
    step_number INT NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    rationale TEXT,
    findings JSONB,
    duration BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inv_agent_steps_inv_id ON investigation_agent_steps (investigation_id);
CREATE INDEX idx_inv_agent_steps_num ON investigation_agent_steps (investigation_id, step_number);

-- 6. Investigation Timeline Events Table
CREATE TABLE investigation_timeline_events (
    id UUID PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_ids JSONB,
    payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inv_timeline_inv_id ON investigation_timeline_events (investigation_id);
CREATE INDEX idx_inv_timeline_time ON investigation_timeline_events (investigation_id, timestamp DESC);
