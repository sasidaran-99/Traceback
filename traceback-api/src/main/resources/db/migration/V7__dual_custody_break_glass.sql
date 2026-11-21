-- =========================================================================
-- V7__dual_custody_break_glass.sql
-- TraceBack 2.0: Dual-Custody Break-Glass Recovery Schema
-- =========================================================================

-- 1. Dual-custody break-glass recovery requests table
CREATE TABLE IF NOT EXISTS remediation_break_glass_requests (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES remediation_operations(id) ON DELETE RESTRICT,
    repository_path TEXT NOT NULL,
    target_ref VARCHAR(255) NOT NULL,
    expected_old_sha VARCHAR(64) NOT NULL,
    proposed_new_sha VARCHAR(64) NOT NULL,
    snapshot_git_sha VARCHAR(64) NOT NULL,
    operation_fencing_token BIGINT NOT NULL,
    requested_decision VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_SECOND_APPROVAL',
    rationale TEXT NOT NULL,

    -- Evidence Bundle Provenance
    evidence_digest VARCHAR(64),
    evidence_bundle_ref TEXT,
    evidence_bundle_version VARCHAR(64),

    -- Initiator Identity (Admin 1)
    initiator_issuer VARCHAR(255) NOT NULL,
    initiator_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Approver / Finalizer Identity (Admin 2)
    approver_issuer VARCHAR(255),
    approver_subject VARCHAR(255),
    resolved_at TIMESTAMP WITH TIME ZONE,
    rejection_reason TEXT,
    execution_notes TEXT,

    -- Cancellation Identity & Timestamp
    cancelled_by_issuer VARCHAR(255),
    cancelled_by_subject VARCHAR(255),
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason TEXT,

    -- 1. Supported Decision: Only KEEP_FROZEN is permitted in Stage 4.3
    CONSTRAINT chk_bg_decision CHECK (
        requested_decision = 'KEEP_FROZEN'
    ),

    -- 2. Lifecycle Status Enum
    CONSTRAINT chk_bg_status CHECK (
        status IN (
            'PENDING_SECOND_APPROVAL',
            'EXECUTED_KEPT_FROZEN',
            'REJECTED',
            'EXPIRED',
            'CANCELLED',
            'STALE_INVALIDATED'
        )
    ),

    -- 3. Fencing Token & Expiration Timing
    CONSTRAINT chk_bg_fencing_token CHECK (operation_fencing_token >= 1),
    CONSTRAINT chk_bg_expiry_timing CHECK (expires_at > created_at),

    -- 4. Hexadecimal Git Object ID Formatting (40-char SHA-1 or 64-char SHA-256)
    CONSTRAINT chk_bg_expected_old_sha CHECK (
        expected_old_sha ~ '^[0-9a-fA-F]{40}$' OR expected_old_sha ~ '^[0-9a-fA-F]{64}$'
    ),
    CONSTRAINT chk_bg_proposed_new_sha CHECK (
        proposed_new_sha ~ '^[0-9a-fA-F]{40}$' OR proposed_new_sha ~ '^[0-9a-fA-F]{64}$'
    ),
    CONSTRAINT chk_bg_snapshot_git_sha CHECK (
        snapshot_git_sha ~ '^[0-9a-fA-F]{40}$' OR snapshot_git_sha ~ '^[0-9a-fA-F]{64}$'
    ),

    -- 5. Evidence Provenance Formatting
    CONSTRAINT chk_bg_evidence_formatting CHECK (
        CASE
            WHEN evidence_digest IS NULL AND evidence_bundle_ref IS NULL AND evidence_bundle_version IS NULL THEN TRUE
            WHEN evidence_digest IS NOT NULL AND evidence_bundle_ref IS NOT NULL AND evidence_bundle_version IS NOT NULL THEN
                evidence_digest ~ '^[0-9a-f]{64}$'
            ELSE FALSE
        END
    ),

    -- 6. Comprehensive NULL-Safe Lifecycle Invariants
    CONSTRAINT chk_bg_lifecycle_invariants CHECK (
        CASE status

            -- A. PENDING_SECOND_APPROVAL
            WHEN 'PENDING_SECOND_APPROVAL' THEN
                approver_issuer IS NULL AND
                approver_subject IS NULL AND
                resolved_at IS NULL AND
                rejection_reason IS NULL AND
                execution_notes IS NULL AND
                cancelled_by_issuer IS NULL AND
                cancelled_by_subject IS NULL AND
                cancelled_at IS NULL AND
                cancellation_reason IS NULL

            -- B. EXECUTED_KEPT_FROZEN
            WHEN 'EXECUTED_KEPT_FROZEN' THEN
                requested_decision = 'KEEP_FROZEN' AND
                approver_issuer IS NOT NULL AND
                approver_subject IS NOT NULL AND
                (approver_issuer <> initiator_issuer OR approver_subject <> initiator_subject) AND
                resolved_at IS NOT NULL AND
                resolved_at >= created_at AND
                rejection_reason IS NULL AND
                cancelled_by_issuer IS NULL AND
                cancelled_by_subject IS NULL AND
                cancelled_at IS NULL AND
                cancellation_reason IS NULL

            -- C. REJECTED
            WHEN 'REJECTED' THEN
                approver_issuer IS NOT NULL AND
                approver_subject IS NOT NULL AND
                (approver_issuer <> initiator_issuer OR approver_subject <> initiator_subject) AND
                resolved_at IS NOT NULL AND
                resolved_at >= created_at AND
                rejection_reason IS NOT NULL AND
                length(trim(rejection_reason)) > 0 AND
                execution_notes IS NULL AND
                cancelled_by_issuer IS NULL AND
                cancelled_by_subject IS NULL AND
                cancelled_at IS NULL AND
                cancellation_reason IS NULL

            -- D. EXPIRED (explicitly requires execution_notes IS NULL)
            WHEN 'EXPIRED' THEN
                approver_issuer IS NULL AND
                approver_subject IS NULL AND
                resolved_at IS NOT NULL AND
                resolved_at >= expires_at AND
                rejection_reason IS NULL AND
                execution_notes IS NULL AND
                cancelled_by_issuer IS NULL AND
                cancelled_by_subject IS NULL AND
                cancelled_at IS NULL AND
                cancellation_reason IS NULL

            -- E. STALE_INVALIDATED
            WHEN 'STALE_INVALIDATED' THEN
                approver_issuer IS NULL AND
                approver_subject IS NULL AND
                resolved_at IS NOT NULL AND
                resolved_at >= created_at AND
                rejection_reason IS NULL AND
                cancelled_by_issuer IS NULL AND
                cancelled_by_subject IS NULL AND
                cancelled_at IS NULL AND
                cancellation_reason IS NULL

            -- F. CANCELLED
            WHEN 'CANCELLED' THEN
                approver_issuer IS NULL AND
                approver_subject IS NULL AND
                resolved_at IS NULL AND
                rejection_reason IS NULL AND
                execution_notes IS NULL AND
                cancelled_by_issuer IS NOT NULL AND
                cancelled_by_subject IS NOT NULL AND
                cancelled_by_issuer = initiator_issuer AND
                cancelled_by_subject = initiator_subject AND
                cancelled_at IS NOT NULL AND
                cancelled_at >= created_at AND
                cancellation_reason IS NOT NULL AND
                length(trim(cancellation_reason)) > 0

            ELSE FALSE
        END
    )
);

-- 2. Partial unique index: at most one active pending request per operation
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_break_glass_op
    ON remediation_break_glass_requests (operation_id)
    WHERE status = 'PENDING_SECOND_APPROVAL';

-- 3. Lookup indexes for operation queries and expiration monitoring
CREATE INDEX IF NOT EXISTS idx_break_glass_op_id
    ON remediation_break_glass_requests (operation_id);

CREATE INDEX IF NOT EXISTS idx_break_glass_status_expiry
    ON remediation_break_glass_requests (status, expires_at);

-- 4. Trigger function enforcing proposal snapshot immutability
CREATE OR REPLACE FUNCTION trg_enforce_break_glass_immutability()
RETURNS TRIGGER AS $$
BEGIN
    -- Prevent modification of terminal rows
    IF OLD.status <> 'PENDING_SECOND_APPROVAL' THEN
        RAISE EXCEPTION 'Cannot modify break-glass request in terminal status: %', OLD.status;
    END IF;

    -- Null-safe verification of all 17 immutable proposal snapshot fields
    IF NEW.id IS DISTINCT FROM OLD.id OR
       NEW.operation_id IS DISTINCT FROM OLD.operation_id OR
       NEW.repository_path IS DISTINCT FROM OLD.repository_path OR
       NEW.target_ref IS DISTINCT FROM OLD.target_ref OR
       NEW.expected_old_sha IS DISTINCT FROM OLD.expected_old_sha OR
       NEW.proposed_new_sha IS DISTINCT FROM OLD.proposed_new_sha OR
       NEW.snapshot_git_sha IS DISTINCT FROM OLD.snapshot_git_sha OR
       NEW.operation_fencing_token IS DISTINCT FROM OLD.operation_fencing_token OR
       NEW.requested_decision IS DISTINCT FROM OLD.requested_decision OR
       NEW.rationale IS DISTINCT FROM OLD.rationale OR
       NEW.evidence_digest IS DISTINCT FROM OLD.evidence_digest OR
       NEW.evidence_bundle_ref IS DISTINCT FROM OLD.evidence_bundle_ref OR
       NEW.evidence_bundle_version IS DISTINCT FROM OLD.evidence_bundle_version OR
       NEW.initiator_issuer IS DISTINCT FROM OLD.initiator_issuer OR
       NEW.initiator_subject IS DISTINCT FROM OLD.initiator_subject OR
       NEW.created_at IS DISTINCT FROM OLD.created_at OR
       NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'Illegal modification of immutable break-glass proposal fields';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_break_glass_immutability ON remediation_break_glass_requests;
CREATE TRIGGER trg_break_glass_immutability
    BEFORE UPDATE ON remediation_break_glass_requests
    FOR EACH ROW EXECUTE FUNCTION trg_enforce_break_glass_immutability();
