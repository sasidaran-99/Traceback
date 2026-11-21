-- =========================================================================
-- V6__remediation_operation_journal.sql
-- TraceBack 2.0: Durable Remediation Operation Journal & Ref Lock Schema
-- =========================================================================

-- 1. Coordination table for target ref serialization and safety freeze
CREATE TABLE IF NOT EXISTS remediation_target_ref_locks (
    ref_name VARCHAR(255) PRIMARY KEY,
    is_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    frozen_reason TEXT,
    frozen_at TIMESTAMP WITH TIME ZONE,
    current_fencing_token BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Durable remediation operations
CREATE TABLE IF NOT EXISTS remediation_operations (
    id UUID PRIMARY KEY,
    investigation_id UUID REFERENCES investigations(id) ON DELETE SET NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    repository_path TEXT NOT NULL,
    target_ref VARCHAR(255) NOT NULL,
    expected_old_sha VARCHAR(64) NOT NULL,
    proposed_new_sha VARCHAR(64) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    worker_id VARCHAR(128),
    fencing_token BIGINT NOT NULL DEFAULT 1,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    initiated_by VARCHAR(128) NOT NULL,
    failure_classification VARCHAR(64),
    failure_details TEXT,
    recovery_status VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_remediation_op_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_remediation_op_status CHECK (status IN (
        'CREATED', 'VALIDATED', 'PRE_GIT_CAS_INTENT', 'GIT_CAS_SUCCEEDED',
        'PERSISTED', 'RECOVERY_REQUIRED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT chk_remediation_op_type CHECK (operation_type IN (
        'BRANCH_PATCH_PROMOTION', 'STAGING_ROLLBACK', 'QUARANTINE_ISOLATION'
    ))
);

CREATE INDEX IF NOT EXISTS idx_remediation_op_target_ref ON remediation_operations (target_ref);
CREATE INDEX IF NOT EXISTS idx_remediation_op_status ON remediation_operations (status);
CREATE INDEX IF NOT EXISTS idx_remediation_op_inv_id ON remediation_operations (investigation_id);
CREATE INDEX IF NOT EXISTS idx_remediation_op_lease ON remediation_operations (status, lease_expires_at);

-- 3. Append-only event journal
CREATE TABLE IF NOT EXISTS remediation_operation_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id UUID NOT NULL REFERENCES remediation_operations(id) ON DELETE CASCADE,
    fencing_token BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    ref_name VARCHAR(255),
    expected_old_sha VARCHAR(64),
    result_sha VARCHAR(64),
    jgit_result VARCHAR(64),
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_remediation_events_op_id ON remediation_operation_events (operation_id, created_at);
