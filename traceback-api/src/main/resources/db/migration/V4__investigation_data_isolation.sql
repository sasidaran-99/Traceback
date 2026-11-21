-- =========================================================================
-- V4__investigation_data_isolation.sql
-- TraceBack 2.0: Investigation Multi-Tenant Isolation & Surrogate PKs
-- Status: APPROVED FOR GATE B EXECUTION
-- Generated: 2026-11-08T18:16:44.509Z
-- =========================================================================


-- -------------------------------------------------------------------------
-- STEP 1: Add surrogate primary key column 'id' to investigation_evidence
-- -------------------------------------------------------------------------
ALTER TABLE investigation_evidence DROP CONSTRAINT IF EXISTS investigation_evidence_pkey;
ALTER TABLE investigation_evidence ADD COLUMN IF NOT EXISTS id UUID DEFAULT gen_random_uuid();
ALTER TABLE investigation_evidence ADD CONSTRAINT investigation_evidence_pkey PRIMARY KEY (id);

-- -------------------------------------------------------------------------
-- STEP 2: Restore true creator ownership to existing displaced evidence rows
-- -------------------------------------------------------------------------
UPDATE investigation_evidence
SET investigation_id = (payload->>'investigationId')::uuid
WHERE payload IS NOT NULL 
  AND payload->>'investigationId' IS NOT NULL 
  AND (payload->>'investigationId') != 'GLOBAL';

-- Explicitly reassign unscoped runtime telemetry artifacts to their original creator (baf36899)
UPDATE investigation_evidence
SET investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'::uuid
WHERE evidence_id IN ('EVD-LOG-SIG', 'EVD-STACK-ROOT')
  AND investigation_id = 'c53c484c-8c43-4bf9-a631-62e60bd28ff5';

-- -------------------------------------------------------------------------
-- STEP 3: Insert investigation-scoped copies for shared / collided evidence
-- -------------------------------------------------------------------------

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-POST-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"9cd50b74-ee3f-451b-b1ea-837b4f6ce195"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-12ef988-OrderService.java' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-12ef988-OrderService.java' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59b7959c-b960-4e23-87cb-5337d846a25b"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-12ef988-OrderService.java' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-POST-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"9cd50b74-ee3f-451b-b1ea-837b4f6ce195"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59b7959c-b960-4e23-87cb-5337d846a25b"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c8e9c281-bac7-4f04-bc0d-4b5d1708da6f'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c8e9c281-bac7-4f04-bc0d-4b5d1708da6f"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"9cd50b74-ee3f-451b-b1ea-837b4f6ce195"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59b7959c-b960-4e23-87cb-5337d846a25b"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c8e9c281-bac7-4f04-bc0d-4b5d1708da6f'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c8e9c281-bac7-4f04-bc0d-4b5d1708da6f"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"9cd50b74-ee3f-451b-b1ea-837b4f6ce195"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59b7959c-b960-4e23-87cb-5337d846a25b"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c8e9c281-bac7-4f04-bc0d-4b5d1708da6f'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c8e9c281-bac7-4f04-bc0d-4b5d1708da6f"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-12ef988' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"9cd50b74-ee3f-451b-b1ea-837b4f6ce195"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"003d70b4-706a-46ae-b2de-ea4a8c0d9c3e"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59b7959c-b960-4e23-87cb-5337d846a25b"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c8e9c281-bac7-4f04-bc0d-4b5d1708da6f'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c8e9c281-bac7-4f04-bc0d-4b5d1708da6f"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-ee51c22' AND investigation_id = 'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '3bc02925-2215-4d9f-a1bf-19b56e139a98'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"3bc02925-2215-4d9f-a1bf-19b56e139a98"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-2a28b00-users.js' AND investigation_id = 'de4417a4-b155-4756-8375-d8160da897ad'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '3bc02925-2215-4d9f-a1bf-19b56e139a98'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"3bc02925-2215-4d9f-a1bf-19b56e139a98"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-9fb2786' AND investigation_id = 'de4417a4-b155-4756-8375-d8160da897ad'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '3bc02925-2215-4d9f-a1bf-19b56e139a98'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"3bc02925-2215-4d9f-a1bf-19b56e139a98"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-9fb2786-users.js' AND investigation_id = 'de4417a4-b155-4756-8375-d8160da897ad'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '3bc02925-2215-4d9f-a1bf-19b56e139a98'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"3bc02925-2215-4d9f-a1bf-19b56e139a98"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-2a28b00' AND investigation_id = 'de4417a4-b155-4756-8375-d8160da897ad'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '74880eda-145c-4ebb-bdd2-630233cc87a5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"74880eda-145c-4ebb-bdd2-630233cc87a5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-WT-MISSING-587cb980af' AND investigation_id = '07162d39-10dc-42aa-b6bb-4525995033f6'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '74880eda-145c-4ebb-bdd2-630233cc87a5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"74880eda-145c-4ebb-bdd2-630233cc87a5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-WT-MISSING-d168d1ee3d' AND investigation_id = '07162d39-10dc-42aa-b6bb-4525995033f6'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '74880eda-145c-4ebb-bdd2-630233cc87a5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"74880eda-145c-4ebb-bdd2-630233cc87a5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-25009aa' AND investigation_id = '07162d39-10dc-42aa-b6bb-4525995033f6'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59bbecc4-41d4-49ef-8b1b-07c34d5153d3'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59bbecc4-41d4-49ef-8b1b-07c34d5153d3"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-COSMETIC-2785ddd' AND investigation_id = '2925282d-63ec-423f-91a9-aedcf016598f'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59bbecc4-41d4-49ef-8b1b-07c34d5153d3'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59bbecc4-41d4-49ef-8b1b-07c34d5153d3"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-COSMETIC-8a7ec51' AND investigation_id = '2925282d-63ec-423f-91a9-aedcf016598f'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59bbecc4-41d4-49ef-8b1b-07c34d5153d3'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59bbecc4-41d4-49ef-8b1b-07c34d5153d3"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-2785ddd' AND investigation_id = '2925282d-63ec-423f-91a9-aedcf016598f'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59bbecc4-41d4-49ef-8b1b-07c34d5153d3'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59bbecc4-41d4-49ef-8b1b-07c34d5153d3"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-SUBSYS-2785ddd' AND investigation_id = '2925282d-63ec-423f-91a9-aedcf016598f'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), '59bbecc4-41d4-49ef-8b1b-07c34d5153d3'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"59bbecc4-41d4-49ef-8b1b-07c34d5153d3"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-8a7ec51' AND investigation_id = '2925282d-63ec-423f-91a9-aedcf016598f'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-SUBSYS-1d6c388' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-SUBSYS-1d6c388' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-c881289' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-c881289' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-afdc06a' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-afdc06a' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-afdc06a-InventoryService.java' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-FRAME-afdc06a-InventoryService.java' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-1d6c388' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-GIT-1d6c388' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-c881289' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-c881289' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-COSMETIC-1d6c388' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CONTRA-COSMETIC-1d6c388' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"c53c484c-8c43-4bf9-a631-62e60bd28ff5"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-afdc06a' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

INSERT INTO investigation_evidence (id, investigation_id, evidence_id, evidence_type, source, original_reference, summary, payload, integrity_hash, created_at)
SELECT gen_random_uuid(), 'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid, evidence_id, evidence_type, source, original_reference, summary, 
       jsonb_set(COALESCE(payload, '{}'::jsonb), '{investigationId}', '"f2dd8f16-9c51-4b13-95fd-179c2900035a"'),
       integrity_hash, created_at
FROM investigation_evidence
WHERE evidence_id = 'EVD-CFG-afdc06a' AND investigation_id = '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------
-- STEP 4: Enforce investigation-scoped unique constraint on evidence
-- -------------------------------------------------------------------------
ALTER TABLE investigation_evidence ADD CONSTRAINT uq_inv_evd_investigation_evidence 
    UNIQUE (investigation_id, evidence_id);

-- -------------------------------------------------------------------------
-- STEP 5: Add surrogate primary key column 'id' to investigation_claims
-- -------------------------------------------------------------------------
ALTER TABLE investigation_claims DROP CONSTRAINT IF EXISTS investigation_claims_pkey;
ALTER TABLE investigation_claims ADD COLUMN IF NOT EXISTS id UUID DEFAULT gen_random_uuid();
ALTER TABLE investigation_claims ADD CONSTRAINT investigation_claims_pkey PRIMARY KEY (id);

-- -------------------------------------------------------------------------
-- STEP 6: Reconstruct displaced historical claims (including CLM-WT-ANOMALY)
-- -------------------------------------------------------------------------

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '5c48eb24-a00e-435a-aff8-88879ea113fb'::uuid,
    'CLM-WT-ANOMALY',
    'Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.',
    'WORKING_TREE_ANOMALY',
    'SUPPORTED',
    '["EVD-WT-MISSING-2d6e2bee8e","EVD-WT-MISSING-bdffc3f87e","EVD-WT-MISSING-66be3177f7","EVD-WT-MISSING-52746cd57a","EVD-WT-MISSING-ab3e2260bc","EVD-WT-MISSING-23930db935","EVD-WT-MISSING-72b8ff0fca","EVD-WT-MISSING-6e26c3c809","EVD-WT-MISSING-5469be9490","EVD-WT-MISSING-0a738f3867","EVD-WT-MISSING-defbce399a","EVD-WT-MISSING-4fe1dd73f4","EVD-WT-MISSING-444fd45e3e","EVD-WT-MISSING-64296bf4b9","EVD-WT-MISSING-f45f2e8903","EVD-WT-MISSING-85f2e0e3f9","EVD-WT-MISSING-da2bee2ba1","EVD-WT-MISSING-78b4199841","EVD-WT-MISSING-995f004262","EVD-WT-MISSING-23ad53addc","EVD-WT-MISSING-acd2782cfd","EVD-WT-MISSING-6a07c32059","EVD-WT-MISSING-1e8659df4f","EVD-WT-MISSING-0667b78032","EVD-WT-MISSING-e8749b3b23","EVD-WT-MISSING-913b36d125","EVD-WT-MISSING-9ba5aa2a6d","EVD-WT-MISSING-370fafbfd7","EVD-WT-MISSING-fc48b52ba1","EVD-WT-MISSING-45f767a1d7","EVD-WT-MISSING-f373b95259","EVD-WT-MISSING-56fc4b9c60","EVD-WT-MISSING-a62a885146","EVD-WT-MISSING-db01660cf4","EVD-WT-MISSING-2d5e3de420","EVD-WT-MISSING-c34253e1e6","EVD-WT-MISSING-e66aa7759a","EVD-WT-MISSING-4dda53d5fc","EVD-WT-MISSING-630b6f69b2","EVD-WT-MISSING-2d6a6599f7","EVD-WT-MISSING-398ebfcfda","EVD-WT-MISSING-fa0b5045a7","EVD-WT-MISSING-c7549526b6","EVD-WT-MISSING-b19c706c74","EVD-WT-MISSING-0ce907d32a","EVD-WT-MISSING-0723e495e2","EVD-WT-MISSING-b592e436e5","EVD-WT-MISSING-759f138af3","EVD-WT-MISSING-5a50c11ab9","EVD-WT-MISSING-d85205da13","EVD-WT-MISSING-b047ca0ab2","EVD-WT-MISSING-9bdeeeb899","EVD-WT-MISSING-b5971c028e","EVD-WT-MISSING-312ca142cd","EVD-WT-MISSING-4aa7db05af","EVD-WT-MISSING-362432946b","EVD-WT-MISSING-2dfc01ffaf","EVD-WT-MISSING-6d57fd14ab","EVD-WT-MISSING-b4b84d75e9","EVD-WT-MISSING-165c0ed3a5","EVD-WT-MISSING-4572a4f707","EVD-WT-MISSING-e364f3f88f","EVD-WT-MISSING-59c594acab","EVD-WT-MISSING-dc904d07a9","EVD-WT-MISSING-2131aca533","EVD-WT-MISSING-39ed3a1812","EVD-WT-MISSING-a4be34fc56","EVD-WT-MISSING-769c78cc73","EVD-WT-MISSING-ecc9eae168","EVD-WT-MISSING-cd4165d86a","EVD-WT-MISSING-f40e401fcb","EVD-WT-MISSING-cba5d5b367","EVD-WT-MISSING-b64dc15a17","EVD-WT-MISSING-6eec69400b","EVD-WT-MISSING-1a038456eb","EVD-WT-MISSING-4cc1d97e77","EVD-WT-MISSING-2b29c25d2f","EVD-WT-MISSING-c8cebc795a","EVD-WT-MISSING-5ce1ab3e8f"]'::jsonb,
    '[]'::jsonb,
    'Claim successfully validated: Supported by 79 verified evidence item(s) with confirmed integrity hashes.',
    '2026-11-07T16:13:19.834670Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '07162d39-10dc-42aa-b6bb-4525995033f6'::uuid,
    'CLM-WT-ANOMALY',
    'Working tree anomaly: 2 tracked files missing from disk (present in HEAD 25009aa). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.',
    'WORKING_TREE_ANOMALY',
    'SUPPORTED',
    '["EVD-WT-MISSING-587cb980af","EVD-WT-MISSING-d168d1ee3d"]'::jsonb,
    '[]'::jsonb,
    'Claim successfully validated: Supported by 2 verified evidence item(s) with confirmed integrity hashes.',
    '2026-11-08T16:57:57.679597Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'::uuid,
    'CLM-ee51c22',
    'Potential contributing change in commit ee51c22 ("perf(db): optimize connection pool parameters for low memory"): modified configuration settings (connection-timeout, minimum-idle, maximum-pool-size) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'CONTRADICTED',
    '["EVD-GIT-ee51c22","EVD-CFG-ee51c22"]'::jsonb,
    '["EVD-CONTRA-POST-ee51c22"]'::jsonb,
    'Claim is refuted by contradictory evidence: [EVD-CONTRA-POST-ee51c22]: Post-failure commit: Commit occurred after the first observed failure (post-failure change)',
    '2026-11-08T16:57:00.190971100Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid,
    'CLM-ee51c22',
    'Potential contributing change in commit ee51c22 ("perf(db): optimize connection pool parameters for low memory"): modified configuration settings (connection-timeout, minimum-idle, maximum-pool-size) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-ee51c22","EVD-CFG-ee51c22"]'::jsonb,
    '["EVD-CONTRA-POST-ee51c22"]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-ee51c22'' belongs to a different investigation (baf36899-090a-4d3c-aeb1-2d21cce5f5bf) rather than active investigation (003d70b4-706a-46ae-b2de-ea4a8c0d9c3e).',
    '2026-11-08T16:57:55.824910700Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid,
    'CLM-ee51c22',
    'Potential contributing change in commit ee51c22 ("perf(db): optimize connection pool parameters for low memory"): modified configuration settings (connection-timeout, minimum-idle, maximum-pool-size) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-ee51c22","EVD-CFG-ee51c22"]'::jsonb,
    '[]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-ee51c22'' belongs to a different investigation (baf36899-090a-4d3c-aeb1-2d21cce5f5bf) rather than active investigation (59b7959c-b960-4e23-87cb-5337d846a25b).',
    '2026-11-08T17:01:20.682395900Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'baf36899-090a-4d3c-aeb1-2d21cce5f5bf'::uuid,
    'CLM-12ef988',
    'Potential contributing change in commit 12ef988 ("feat: initial commit with standard Hikari connection pool configuration"): modified configuration settings (spring, connection-timeout, server, port, datasource, minimum-idle, maximum-pool-size, hikari) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'CONTRADICTED',
    '["EVD-GIT-12ef988","EVD-FRAME-12ef988-OrderService.java","EVD-CFG-12ef988"]'::jsonb,
    '["EVD-CONTRA-POST-12ef988"]'::jsonb,
    'Claim is refuted by contradictory evidence: [EVD-CONTRA-POST-12ef988]: Post-failure commit: Commit occurred after the first observed failure (post-failure change)',
    '2026-11-08T16:57:00.192027900Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '003d70b4-706a-46ae-b2de-ea4a8c0d9c3e'::uuid,
    'CLM-12ef988',
    'Potential contributing change in commit 12ef988 ("feat: initial commit with standard Hikari connection pool configuration"): modified configuration settings (spring, connection-timeout, server, port, datasource, minimum-idle, maximum-pool-size, hikari) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-12ef988","EVD-FRAME-12ef988-OrderService.java","EVD-CFG-12ef988"]'::jsonb,
    '["EVD-CONTRA-POST-12ef988"]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-12ef988'' belongs to a different investigation (baf36899-090a-4d3c-aeb1-2d21cce5f5bf) rather than active investigation (003d70b4-706a-46ae-b2de-ea4a8c0d9c3e).',
    '2026-11-08T16:57:55.824910700Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '59b7959c-b960-4e23-87cb-5337d846a25b'::uuid,
    'CLM-12ef988',
    'Potential contributing change in commit 12ef988 ("feat: initial commit with standard Hikari connection pool configuration"): modified configuration settings (spring, connection-timeout, server, port, datasource, minimum-idle, maximum-pool-size, hikari) aligning with runtime failure symptom (java.sql.SQLTimeoutException: Connection timeout after 2000ms.). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-12ef988","EVD-FRAME-12ef988-OrderService.java","EVD-CFG-12ef988"]'::jsonb,
    '[]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-12ef988'' belongs to a different investigation (baf36899-090a-4d3c-aeb1-2d21cce5f5bf) rather than active investigation (59b7959c-b960-4e23-87cb-5337d846a25b).',
    '2026-11-08T17:01:20.682928300Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'de4417a4-b155-4756-8375-d8160da897ad'::uuid,
    'CLM-9fb2786',
    'Code regression in commit 9fb2786 ("feat(users): add user profile endpoint with nested role access"): modified file matches runtime crash stack frame. Available evidence supports code correlation, but does not independently establish active deployment timing.',
    'DETERMINISTIC_CORRELATION',
    'SUPPORTED',
    '["EVD-GIT-9fb2786","EVD-FRAME-9fb2786-users.js"]'::jsonb,
    '[]'::jsonb,
    'Claim successfully validated: Supported by 2 verified evidence item(s) with confirmed integrity hashes.',
    '2026-11-08T16:57:56.806399900Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'de4417a4-b155-4756-8375-d8160da897ad'::uuid,
    'CLM-2a28b00',
    'Code regression in commit 2a28b00 ("feat: initial express user service"): modified file matches runtime crash stack frame. Available evidence supports code correlation, but does not independently establish active deployment timing.',
    'DETERMINISTIC_CORRELATION',
    'SUPPORTED',
    '["EVD-GIT-2a28b00","EVD-FRAME-2a28b00-users.js"]'::jsonb,
    '[]'::jsonb,
    'Claim successfully validated: Supported by 2 verified evidence item(s) with confirmed integrity hashes.',
    '2026-11-08T16:57:56.808049800Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '07162d39-10dc-42aa-b6bb-4525995033f6'::uuid,
    'CLM-25009aa',
    'Candidate commit 25009aa ("feat: initial commit with 5 core project files") shows weak correlation (heuristic confidence: 15%).',
    'DETERMINISTIC_CORRELATION',
    'SUPPORTED',
    '["EVD-GIT-25009aa"]'::jsonb,
    '[]'::jsonb,
    'Claim successfully validated: Supported by 1 verified evidence item(s) with confirmed integrity hashes.',
    '2026-11-08T16:57:57.678528500Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '2925282d-63ec-423f-91a9-aedcf016598f'::uuid,
    'CLM-8a7ec51',
    'Candidate commit 8a7ec51 ("docs: initial architectural documentation") shows weak correlation (heuristic confidence: 6%).',
    'DETERMINISTIC_CORRELATION',
    'PARTIALLY_SUPPORTED',
    '["EVD-GIT-8a7ec51"]'::jsonb,
    '["EVD-CONTRA-COSMETIC-8a7ec51"]'::jsonb,
    'Claim has supporting evidence, but is dampened by 1 contradictory evidence observation(s).',
    '2026-11-08T16:57:58.435925500Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '2925282d-63ec-423f-91a9-aedcf016598f'::uuid,
    'CLM-2785ddd',
    'Candidate commit 2785ddd ("docs(api): update authentication header documentation") shows weak correlation (heuristic confidence: 0%).',
    'DETERMINISTIC_CORRELATION',
    'CONTRADICTED',
    '["EVD-GIT-2785ddd"]'::jsonb,
    '["EVD-CONTRA-COSMETIC-2785ddd","EVD-CONTRA-SUBSYS-2785ddd"]'::jsonb,
    'Claim is refuted by contradictory evidence: [EVD-CONTRA-COSMETIC-2785ddd]: Cosmetic commit: Commit modified only cosmetic or documentation files; zero functional code or configuration impact [EVD-CONTRA-SUBSYS-2785ddd]: Subsystem contradiction: Commit touched AUTHENTICATION with zero database or stack trace overlap; zero database or stack trace overlap',
    '2026-11-08T16:57:58.436431300Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'::uuid,
    'CLM-c881289',
    'Potential contributing change in commit c881289 ("refactor(config): severely restrict connection pool size and timeout"): modified configuration settings (spring.datasource.hikari.maximum-pool-size, spring.datasource.hikari.connection-timeout) aligning with runtime failure symptom (2026-11-08 10:30:00.000 ERROR 999 --- [inventory-pool] org.postgresql.Driver : org.postgresql.util.PSQLException: FATAL: remaining connection slots are reserved for non-replication superuser connections: ). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'CONTRADICTED',
    '["EVD-GIT-c881289","EVD-CFG-c881289"]'::jsonb,
    '["EVD-CONTRA-POST-c881289"]'::jsonb,
    'Claim is refuted by contradictory evidence: [EVD-CONTRA-POST-c881289]: Post-failure commit: Commit occurred after the first observed failure (post-failure change)',
    '2026-11-08T16:57:59.274209200Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid,
    'CLM-c881289',
    'Potential contributing change in commit c881289 ("refactor(config): severely restrict connection pool size and timeout"): modified configuration settings (spring.datasource.hikari.maximum-pool-size, spring.datasource.hikari.connection-timeout) aligning with runtime failure symptom (2026-11-08 22:24:50.000 ERROR 999 --- [inventory-pool] org.postgresql.Driver : org.postgresql.util.PSQLException: FATAL: remaining connection slots are reserved for non-replication superuser connections: ). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-c881289","EVD-CFG-c881289"]'::jsonb,
    '[]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-c881289'' belongs to a different investigation (5e53f60a-5f03-458c-81e5-b3c5660f7c3a) rather than active investigation (f2dd8f16-9c51-4b13-95fd-179c2900035a).',
    '2026-11-08T17:01:21.823205900Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'::uuid,
    'CLM-1d6c388',
    'Candidate commit 1d6c388 ("style(ui): update primary button brand color and border radius") shows weak correlation (heuristic confidence: 0%).',
    'DETERMINISTIC_CORRELATION',
    'CONTRADICTED',
    '["EVD-GIT-1d6c388"]'::jsonb,
    '["EVD-CONTRA-POST-1d6c388","EVD-CONTRA-COSMETIC-1d6c388","EVD-CONTRA-SUBSYS-1d6c388"]'::jsonb,
    'Claim is refuted by contradictory evidence: [EVD-CONTRA-POST-1d6c388]: Post-failure commit: Commit occurred after the first observed failure (post-failure change) [EVD-CONTRA-COSMETIC-1d6c388]: Cosmetic commit: Commit modified only cosmetic or documentation files; zero functional code or configuration impact [EVD-CONTRA-SUBSYS-1d6c388]: Subsystem contradiction: Commit touched UI with zero database or stack trace overlap; zero database or stack trace overlap',
    '2026-11-08T16:57:59.274209200Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid,
    'CLM-1d6c388',
    'Candidate commit 1d6c388 ("style(ui): update primary button brand color and border radius") shows weak correlation (heuristic confidence: 0%).',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-1d6c388"]'::jsonb,
    '["EVD-CONTRA-COSMETIC-1d6c388","EVD-CONTRA-SUBSYS-1d6c388"]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-1d6c388'' belongs to a different investigation (5e53f60a-5f03-458c-81e5-b3c5660f7c3a) rather than active investigation (f2dd8f16-9c51-4b13-95fd-179c2900035a).',
    '2026-11-08T17:01:21.823205900Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    '5e53f60a-5f03-458c-81e5-b3c5660f7c3a'::uuid,
    'CLM-afdc06a',
    'Potential contributing change in commit afdc06a ("feat: initial inventory service and assets"): modified configuration settings (spring.datasource.hikari.maximum-pool-size, spring.datasource.hikari.connection-timeout) aligning with runtime failure symptom (2026-11-08 10:30:00.000 ERROR 999 --- [inventory-pool] org.postgresql.Driver : org.postgresql.util.PSQLException: FATAL: remaining connection slots are reserved for non-replication superuser connections: ). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'CONTRADICTED',
    '["EVD-GIT-afdc06a","EVD-FRAME-afdc06a-InventoryService.java","EVD-CFG-afdc06a"]'::jsonb,
    '["EVD-CONTRA-POST-afdc06a"]'::jsonb,
    'Claim is refuted by contradictory evidence: [EVD-CONTRA-POST-afdc06a]: Post-failure commit: Commit occurred after the first observed failure (post-failure change)',
    '2026-11-08T16:57:59.274209200Z'::timestamptz
) ON CONFLICT DO NOTHING;

INSERT INTO investigation_claims (
    id, investigation_id, claim_id, statement, claim_type,
    validation_status, supporting_evidence_ids, contradicting_evidence_ids,
    validation_explanation, created_at
) VALUES (
    gen_random_uuid(),
    'f2dd8f16-9c51-4b13-95fd-179c2900035a'::uuid,
    'CLM-afdc06a',
    'Potential contributing change in commit afdc06a ("feat: initial inventory service and assets"): modified configuration settings (spring.datasource.hikari.maximum-pool-size, spring.datasource.hikari.connection-timeout) aligning with runtime failure symptom (2026-11-08 22:24:50.000 ERROR 999 --- [inventory-pool] org.postgresql.Driver : org.postgresql.util.PSQLException: FATAL: remaining connection slots are reserved for non-replication superuser connections: ). The available evidence supports investigating this change, but does not independently establish that the modified configuration was deployed or active during the incident.',
    'DETERMINISTIC_CORRELATION',
    'UNSUPPORTED',
    '["EVD-GIT-afdc06a","EVD-FRAME-afdc06a-InventoryService.java","EVD-CFG-afdc06a"]'::jsonb,
    '[]'::jsonb,
    'Validation failed: Evidence ID ''EVD-GIT-afdc06a'' belongs to a different investigation (5e53f60a-5f03-458c-81e5-b3c5660f7c3a) rather than active investigation (f2dd8f16-9c51-4b13-95fd-179c2900035a).',
    '2026-11-08T17:01:21.823205900Z'::timestamptz
) ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------
-- STEP 7: Enforce investigation-scoped unique constraint on claims
-- -------------------------------------------------------------------------
ALTER TABLE investigation_claims ADD CONSTRAINT uq_inv_clm_investigation_claim 
    UNIQUE (investigation_id, claim_id);

-- -------------------------------------------------------------------------
-- STEP 8: Create compound indexes for high-speed scoped query performance
-- -------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_inv_evd_scoped ON investigation_evidence (investigation_id, evidence_id);
CREATE INDEX IF NOT EXISTS idx_inv_clm_scoped ON investigation_claims (investigation_id, claim_id);

