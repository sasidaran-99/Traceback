-- =========================================================================
-- V5__investigation_hypotheses.sql
-- TraceBack 2.0: Investigation-Scoped Hypothesis Persistence Schema
-- =========================================================================

CREATE TABLE IF NOT EXISTS investigation_hypotheses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hypothesis_id UUID NOT NULL,
    investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
    rank INTEGER NOT NULL,
    confidence_score DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    verification_plan TEXT,
    counter_evidence TEXT,
    suspected_commit_sha VARCHAR(64),
    supporting_evidence_ids JSONB DEFAULT '[]'::jsonb,
    contradicting_evidence_ids JSONB DEFAULT '[]'::jsonb,
    matched_rules JSONB DEFAULT '[]'::jsonb,
    rule_match_reasons JSONB DEFAULT '{}'::jsonb,
    penalty_reasons JSONB DEFAULT '[]'::jsonb,
    ranking_explanation TEXT,
    confidence_breakdown JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inv_hyp_investigation_hypothesis UNIQUE (investigation_id, hypothesis_id)
);

CREATE INDEX IF NOT EXISTS idx_inv_hypotheses_inv_id ON investigation_hypotheses (investigation_id);
CREATE INDEX IF NOT EXISTS idx_inv_hypotheses_rank ON investigation_hypotheses (investigation_id, rank);
CREATE INDEX IF NOT EXISTS idx_inv_hypotheses_commit ON investigation_hypotheses (investigation_id, suspected_commit_sha);
