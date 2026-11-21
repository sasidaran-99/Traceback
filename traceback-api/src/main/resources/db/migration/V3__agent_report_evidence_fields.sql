-- V3__agent_report_evidence_fields.sql
-- Traceback 2.0 Agent Report Evidence Fields & Timeline Consistency

-- 1. Extend investigation_agent_reports with primary_suspected_root_cause and evidence ID collections
ALTER TABLE investigation_agent_reports ADD COLUMN IF NOT EXISTS primary_suspected_root_cause TEXT;
ALTER TABLE investigation_agent_reports ADD COLUMN IF NOT EXISTS supporting_evidence_ids JSONB;
ALTER TABLE investigation_agent_reports ADD COLUMN IF NOT EXISTS contradictory_evidence_ids JSONB;

-- 2. Backfill existing TacitIQ report if missing root cause
UPDATE investigation_agent_reports
SET primary_suspected_root_cause = 'Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.'
WHERE report_id = 'REP-e2803caa' AND primary_suspected_root_cause IS NULL;
