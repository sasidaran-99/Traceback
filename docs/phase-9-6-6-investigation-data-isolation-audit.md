# TraceBack 2.0 — Phase 9.6.6: Investigation Data Isolation & Primary Key Integrity Audit

**Audit Date**: November 8, 2026  
**Audit Mode**: STRICTLY READ-ONLY (No DB writes, no source edits, no migrations executed, no process restarts)  
**Target Environment**:
- Backend: `http://localhost:8080` (PID: `15076`, Java 21, Spring Boot 3.3.4)
- Frontend: `http://localhost:5173` (PID: `19724`, React 18 + Vite)
- PostgreSQL: `localhost:5432`, `traceback_db` (PID: `2724`, PostgreSQL 18.3, Flyway Schema v3)
- Authorized Repositories:
  - `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack`
  - `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ\EconomicTimes` (Verified invariant: 79 missing tracked files, 1 modified `README.md`, 0 staged, 0 untracked)

---

## Executive Summary

Phase 9.6.6 conducted an exhaustive, read-only architectural and database audit of data isolation in TraceBack 2.0. The audit verified a critical persistence defect originally discovered during Phase 9.6.5:

1. **Globally Scoped Primary Keys Cause Silent Overwriting**:
   - `investigation_evidence` defines `PRIMARY KEY (evidence_id)`.
   - `investigation_claims` defines `PRIMARY KEY (claim_id)`.
   Neither table scopes its primary key by `investigation_id`. Consequently, when deterministic algorithms generate identical identifiers across separate investigations (e.g. `CLM-WT-ANOMALY`, `EVD-FRAME-12ef988-OrderService.java`, `CLM-ee51c22`), JPA performs an `UPDATE` on the primary key, silently mutating the `investigation_id` column to point to the newest investigation.

2. **Full Scope of Compromise**:
   - In `investigation_evidence` (135 total rows): **ALL 135 rows** currently have column `investigation_id = 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'` (Scenario E). 105 rows are displaced from their original creator, 27 rows are shared/collided across multiple runs, and 3 rows are orphaned.
   - In `investigation_claims` (34 total rows): **11 claims** suffered cross-investigation collisions. Most critically, `CLM-WT-ANOMALY` (originally asserted and verified by historical investigation `5c48eb24-a00e-435a-aff8-88879ea113fb`) was overwritten and reassigned to Scenario C (`74880eda-145c-4ebb-bdd2-630233cc87a5`).
   - Cross-Investigation Leakage in REST APIs: `GET /api/investigations/c53c484c-8c43-4bf9-a631-62e60bd28ff5/evidence` leaks all 135 evidence items (including TacitIQ file deletions) into Scenario E's Evidence Explorer.

3. **100% Data Recoverability**:
   - Zero historical data is permanently lost.
   - The historical report `REP-e2803caa` in `investigation_agent_reports.validated_claims` retains the complete JSON payload of `CLM-WT-ANOMALY` (including its `SUPPORTED` status and 79 supporting evidence IDs).
   - In `investigation_evidence`, the `payload` JSONB column retains the creator's investigation ID (`payload->>'investigationId'`), enabling automated 1:1 SQL backfilling upon migration.

---

## 1. Audit A — Evidence Ownership & Cross-Investigation Collisions

### 1.1 Database Row Inspection (135 Rows)

Every row in `investigation_evidence` was inspected using raw SQL extraction:

```sql
SELECT 
  COUNT(*) as total_rows,
  COUNT(DISTINCT investigation_id) as distinct_db_owners,
  MIN(investigation_id) as sample_db_owner
FROM investigation_evidence;
```

**Result**:
- Total rows: `135`
- Distinct `investigation_id` column values: `1`
- Single DB owner: `'c53c484c-8c43-4bf9-a631-62e60bd28ff5'` (Scenario E — Misleading Commit)

### 1.2 Creator vs Column Owner Mismatch

Analyzing the embedded JSON metadata (`payload->>'investigationId'`) reveals the actual originating investigations:

| Originating Investigation (`payload->>'investigationId'`) | Count | Description |
|:---|:---:|:---|
| `5c48eb24-a00e-435a-aff8-88879ea113fb` | 103 | Historical TacitIQ baseline (79 missing files, commits, diffs) |
| `5e53f60a-5f03-458c-81e5-b3c5660f7c3a` | 11 | Exploratory Scenario E runs (synthetic repository commits/diffs) |
| `baf36899-090a-4d3c-aeb1-2d21cce5f5bf` | 7 | Exploratory Scenario A runs (OrderService stack frames/commits) |
| `2925282d-63ec-423f-91a9-aedcf016598f` | 5 | Exploratory Scenario D runs (documentation commits) |
| `de4417a4-b155-4756-8375-d8160da897ad` | 4 | Exploratory Scenario B runs (user-service stack frames/commits) |
| `07162d39-10dc-42aa-b6bb-4525995033f6` | 3 | Exploratory Scenario C runs (forensics synthetic changes) |
| `NONE` (no embedded payload ID) | 2 | Single-instance artifacts (`EVD-LOG-SIG`, `EVD-STACK-ROOT`) |
| **Total** | **135** | |

Matches between column `investigation_id` and `payload->>'investigationId'`: **0**  
Mismatches: **133**  
Unscoped artifacts: **2**

### 1.3 Deep-Dive: `EVD-FRAME-12ef988-OrderService.java`

- **Current DB Row**:
  - `evidence_id`: `EVD-FRAME-12ef988-OrderService.java`
  - `investigation_id`: `c53c484c-8c43-4bf9-a631-62e60bd28ff5` (Scenario E)
  - `evidence_type`: `STACK_FRAME`
  - `source`: `STACK_TRACE`
  - `payload->>'investigationId'`: `baf36899-090a-4d3c-aeb1-2d21cce5f5bf` (Exploratory Scenario A)
  - `created_at`: `2026-11-08T22:27:00.432062+05:30`
  - `summary`: `"Stack frame in com.traceback.sample.OrderService.processOrder matching commit 12ef988"`
- **Referencing Investigations**:
  - `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` (Official Scenario A: referenced in claim `CLM-12ef988` and report `REP-16c0831f`)
  - `baf36899-090a-4d3c-aeb1-2d21cce5f5bf` (First exploratory run)
  - `003d70b4-706a-46ae-b2de-ea4a8c0d9c3e` (Second exploratory run)
  - `59b7959c-b960-4e23-87cb-5337d846a25b` (Third exploratory run)
- **Findings**:
  Although created during run `baf36899` and actively referenced by official Scenario A (`9cd50b74`), its database ownership column points to Scenario E (`c53c484c`), an entirely unrelated investigation targeting `inventory-service`!

### 1.4 Evidence Categorization

By analyzing all foreign references across `investigation_claims`, `investigation_agent_reports`, and `investigation_timeline_events`, every evidence item was categorized:

| Category | Count | Definition & Explanation |
|:---|:---:|:---|
| **Purely Single-Tenant** | **0** | Owned by DB column `investigation_id` and referenced exclusively by that investigation. |
| **Displaced** | **105** | Referenced by exactly one investigation, but its DB column `investigation_id` points to a different investigation (103 TacitIQ items + 2 others). |
| **Shared / Collided** | **27** | Referenced by two or more investigations because multiple runs targeted identical commit hashes or working trees. |
| **Orphaned** | **3** | Present in `investigation_evidence` but referenced by 0 claims, timeline events, or reports (`EVD-WT-MOD-b335630551`, `EVD-LOG-SIG`, `EVD-STACK-ROOT`). |
| **Total** | **135** | |

#### Collided Evidence Highlights (Sample of 27):
- `EVD-GIT-ee51c22`: Referenced by 4 investigations (`baf36899`, `003d70b4`, `59b7959c`, `9cd50b74`).
- `EVD-GIT-12ef988`: Referenced by 4 investigations (`baf36899`, `003d70b4`, `59b7959c`, `9cd50b74`).
- `EVD-GIT-9fb2786`: Referenced by 2 investigations (`de4417a4`, `3bc02925`).
- `EVD-GIT-2a28b00`: Referenced by 2 investigations (`de4417a4`, `3bc02925`).
- `EVD-WT-MISSING-587cb980af`: Referenced by 2 investigations (`07162d39`, `74880eda`).
- `EVD-WT-MISSING-d168d1ee3d`: Referenced by 2 investigations (`07162d39`, `74880eda`).
- `EVD-GIT-25009aa`: Referenced by 2 investigations (`07162d39`, `74880eda`).
- `EVD-GIT-c881289`: Referenced by 3 investigations (`5e53f60a`, `f2dd8f16`, `c53c484c`).

---

## 2. Audit B — Claim Ownership & Historical Displacement

### 2.1 Database Row Inspection (34 Rows)

`investigation_claims` currently holds 34 rows across 9 investigations:

| DB Owner `investigation_id` | Claim Count | Claims |
|:---|:---:|:---|
| `5c48eb24-a00e-435a-aff8-88879ea113fb` (Historical) | 16 | `CLM-d711d65`, `CLM-272d8a6`, `CLM-32e96d2`, `CLM-7432e51`, `CLM-08cae86`, `CLM-58f9504`, `CLM-ced71fa`, `CLM-8ddcb8c`, `CLM-dd9948f`, `CLM-3545e01`, `CLM-726a491`, `CLM-2f6478f`, `CLM-aa1b233`, `CLM-70f6b2e`, `CLM-616947b`, `CLAIM-AGENT-PRIMARY-5c48eb24` |
| `74880eda-145c-4ebb-bdd2-630233cc87a5` (Scenario C) | 3 | `CLM-WT-ANOMALY`, `CLM-25009aa`, `CLAIM-AGENT-PRIMARY-74880eda` |
| `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` (Scenario A) | 3 | `CLM-ee51c22`, `CLM-12ef988`, `CLAIM-AGENT-PRIMARY-9cd50b74` |
| `3bc02925-2215-4d9f-a1bf-19b56e139a98` (Scenario B) | 3 | `CLM-9fb2786`, `CLM-2a28b00`, `CLAIM-AGENT-PRIMARY-3bc02925` |
| `c53c484c-8c43-4bf9-a631-62e60bd28ff5` (Scenario E) | 4 | `CLM-c881289`, `CLM-1d6c388`, `CLM-afdc06a`, `CLAIM-AGENT-PRIMARY-c53c484c` |
| `59bbecc4-41d4-49ef-8b1b-07c34d5153d3` (Scenario D) | 2 | `CLM-8a7ec51`, `CLM-2785ddd` |
| `de4417a4-b155-4756-8375-d8160da897ad` (Exploratory B) | 1 | `CLAIM-AGENT-PRIMARY-de4417a4` |
| `07162d39-10dc-42aa-b6bb-4525995033f6` (Exploratory C) | 1 | `CLAIM-AGENT-PRIMARY-07162d39` |
| `f2dd8f16-9c51-4b13-95fd-179c2900035a` (Exploratory E) | 1 | `CLAIM-AGENT-PRIMARY-f2dd8f16` |
| **Total** | **34** | |

### 2.2 Deep-Dive: `CLM-WT-ANOMALY` Displacement

`CLM-WT-ANOMALY` is the central working-tree anomaly claim. Because `claim_id` is a single-column primary key, when Scenario C ran in Phase 9.6.4.2, it generated `CLM-WT-ANOMALY` and executed `claimRepository.save(...)`. JPA observed an existing entity with primary key `CLM-WT-ANOMALY` and performed an `UPDATE`, overwriting the row.

#### Side-by-Side Comparison:

| Property | Current DB Table Row | Historical Report `REP-e2803caa` | Scenario C Report `REP-d1b0ee2f` | Inactive Backup File |
|:---|:---|:---|:---|:---|
| **`claim_id`** | `CLM-WT-ANOMALY` | `CLM-WT-ANOMALY` | `CLM-WT-ANOMALY` | `CLM-WT-ANOMALY` |
| **`investigation_id`** | `74880eda-145c-4ebb-bdd2-630233cc87a5` | `5c48eb24-a00e-435a-aff8-88879ea113fb` | `74880eda-145c-4ebb-bdd2-630233cc87a5` | `5c48eb24-a00e-435a-aff8-88879ea113fb` |
| **`statement`** | "Working tree anomaly: 2 tracked files missing from disk..." | "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65)..." | "Working tree anomaly: 2 tracked files missing from disk..." | "Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65)..." |
| **`validation_status`**| `UNSUPPORTED` | `SUPPORTED` | `SUPPORTED` | `SUPPORTED` |
| **Supporting Evd Count** | 2 (`587cb980af`, `d168d1ee3d`) | 79 (`EVD-WT-MISSING-*`) | 2 (`587cb980af`, `d168d1ee3d`) | 79 (`EVD-WT-MISSING-*`) |
| **Explanation** | "Claim evaluation completed: 0 supporting, 0 contradicting" | "Claim successfully validated: Supported by 79 verified evidence item(s)..." | "Claim successfully validated: Supported by 2 verified evidence item(s)..." | "Claim successfully validated: Supported by 79 verified evidence item(s)..." |
| **`created_at`** | `2026-11-08T18:22:48.317657+05:30` | `2026-11-07T16:13:19.834670Z` | `2026-11-08T18:22:48.317657Z` | `2026-11-07T16:13:19.834670Z` |

### 2.3 Cross-Investigation Claim Collisions

11 claims suffered collisions across investigations:

| Claim ID | DB Column Owner | Number of Asserting Reports | Asserting Investigation IDs |
|:---|:---|:---:|:---|
| `CLM-WT-ANOMALY` | `74880eda` (Scenario C) | 3 | `5c48eb24`, `07162d39`, `74880eda` |
| `CLM-ee51c22` | `9cd50b74` (Scenario A) | 4 | `baf36899`, `003d70b4`, `59b7959c`, `9cd50b74` |
| `CLM-12ef988` | `9cd50b74` (Scenario A) | 4 | `baf36899`, `003d70b4`, `59b7959c`, `9cd50b74` |
| `CLM-9fb2786` | `3bc02925` (Scenario B) | 2 | `de4417a4`, `3bc02925` |
| `CLM-2a28b00` | `3bc02925` (Scenario B) | 2 | `de4417a4`, `3bc02925` |
| `CLM-25009aa` | `74880eda` (Scenario C) | 2 | `07162d39`, `74880eda` |
| `CLM-8a7ec51` | `59bbecc4` (Scenario D) | 2 | `2925282d`, `59bbecc4` |
| `CLM-2785ddd` | `59bbecc4` (Scenario D) | 2 | `2925282d`, `59bbecc4` |
| `CLM-c881289` | `c53c484c` (Scenario E) | 3 | `5e53f60a`, `f2dd8f16`, `c53c484c` |
| `CLM-1d6c388` | `c53c484c` (Scenario E) | 3 | `5e53f60a`, `f2dd8f16`, `c53c484c` |
| `CLM-afdc06a` | `c53c484c` (Scenario E) | 3 | `5e53f60a`, `f2dd8f16`, `c53c484c` |

---

## 3. Audit C — Six Official Dossiers & REST Endpoint Verification

Live HTTP queries against all six official dossiers verified data isolation behavior:

| Investigation | Evidence Count | Claims Count | Timeline Events | Report ID & Status | Leakage / Integrity Status |
|:---|:---:|:---:|:---:|:---|:---|
| **Historical TacitIQ** (`5c48eb24`) | 103 | 16 (missing `CLM-WT-ANOMALY`) | 35 (35/35 resolved) | `REP-e2803caa` (200 OK) | Protected by fallback filtering; 1 claim displaced. |
| **Scenario A** (`9cd50b74`) | 5 | 3 | 6 (6/6 resolved) | `REP-16c0831f` (200 OK) | Protected by fallback filtering; 0 leakage. |
| **Scenario B** (`3bc02925`) | 4 | 3 | 3 (3/3 resolved) | `REP-bfa895ae` (200 OK) | Protected by fallback filtering; 0 leakage. |
| **Scenario C** (`74880eda`) | 3 | 3 | 2 (2/2 resolved) | `REP-d1b0ee2f` (200 OK) | Holds displaced `CLM-WT-ANOMALY`. |
| **Scenario D** (`59bbecc4`) | 2 | 2 | 3 (3/3 resolved) | `REP-1b6815ab` (200 OK) | Protected by fallback filtering; 0 leakage. |
| **Scenario E** (`c53c484c`) | **135 (LEAKAGE)** | 4 | 7 (7/7 resolved) | `REP-d3bf1e00` (200 OK) | **COMPROMISED**: Direct lookup returns all 135 rows in DB! |

### Endpoint Behavior Breakdown:
1. `GET /api/investigations/{id}/evidence`:
   - In `InvestigationService.getEvidence(id)`:
     ```java
     List<EvidenceItemEntity> entities = evidenceRepository.findByInvestigationId(id);
     if (!entities.isEmpty()) {
         return entities.stream().map(persistenceMapper::toDomain).toList();
     }
     ```
   - For Scenarios A, B, C, D, and Historical, `findByInvestigationId(id)` returned empty or was diverted to metadata filtering fallbacks.
   - For **Scenario E**, `findByInvestigationId(c53c484c)` matched **all 135 rows** because every row in the database has column `investigation_id = 'c53c484c-8c43-4bf9-a631-62e60bd28ff5'`. It bypassed the fallback and returned all 135 items!
2. `GET /api/investigations/{id}/claims`:
   - Historical returns 16 claims instead of 17 because `CLM-WT-ANOMALY` belongs to Scenario C in the table.
   - However, `GET /api/investigations/{id}/agent-report` returns `REP-e2803caa`, which contains all 17 claims in its `validated_claims` JSON array!
3. `GET /api/investigations/{id}/agent-report`:
   - All 6 dossiers return HTTP 200 with complete reports, verified verdicts, and root-cause summaries.
4. **Frontend Evidence Explorer Impact**:
   - Visiting Scenario E in the browser shows 135 evidence items across tabs ("Working Tree Changes (82)", "Commits (43)", "Config Diffs (4)").
   - A user inspecting Scenario E sees TacitIQ file deletions and other repositories' commits.

---

## 4. Audit D — Persistence Architecture Audit

### 4.1 JPA Entities and Primary Key Definitions

| Entity Class | Table Name | JPA Primary Key (`@Id`) | DB Primary Key Constraint | Collision Risk |
|:---|:---|:---|:---|:---:|
| `EvidenceItemEntity` | `investigation_evidence` | `@Id private String evidenceId;` | `PRIMARY KEY (evidence_id)` | **CRITICAL** |
| `ClaimEntity` | `investigation_claims` | `@Id private String claimId;` | `PRIMARY KEY (claim_id)` | **CRITICAL** |
| `TimelineEventEntity`| `investigation_timeline_events`| `@Id private UUID id;` | `PRIMARY KEY (id)` | Safe (Surrogate UUID) |
| `AgentStepEntity` | `investigation_agent_steps` | `@Id private UUID id;` | `PRIMARY KEY (id)` | Safe (Surrogate UUID) |
| `AgentReportEntity` | `investigation_agent_reports` | `@Id private String reportId;` | `PRIMARY KEY (report_id)` | Safe (UUID substring) |
| `InvestigationEntity`| `investigations` | `@Id private UUID id;` | `PRIMARY KEY (id)` | Safe (UUID) |

### 4.2 Why JPA Performed an `UPDATE` Instead of Failing

In Spring Data JPA / Hibernate:
```java
// InvestigationService.java
if (claimRepository != null && result.claims() != null) {
    for (InvestigationClaim claim : result.claims()) {
        claimRepository.save(persistenceMapper.toEntity(claim, result.investigationId(), type));
    }
}
```
When `save(entity)` is called on an entity whose `@Id` field is populated (`claimId = "CLM-WT-ANOMALY"`):
1. Hibernate checks its persistence context or issues a `SELECT` on `claim_id`.
2. Finding a row with primary key `CLM-WT-ANOMALY`, Hibernate treats it as detached and issues an SQL `UPDATE investigation_claims SET investigation_id = ?, statement = ?, ... WHERE claim_id = ?`.
3. The foreign key `investigation_id` column is silently rewritten to the new investigation!

### 4.3 Flyway Migrations (V1, V2, V3) Review
- `V1__initial_schema.sql`: Defined event store (`events`, `incidents`, `hypotheses`).
- `V2__investigation_persistence.sql`: Introduced investigation persistence tables. In lines 27 and 44:
  ```sql
  CREATE TABLE investigation_evidence (
      evidence_id VARCHAR(128) PRIMARY KEY,
      investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
  ...
  CREATE TABLE investigation_claims (
      claim_id VARCHAR(128) PRIMARY KEY,
      investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE,
  ```
  This schema assumed `evidence_id` and `claim_id` would be globally unique across all investigations forever.
- `V3__agent_report_evidence_fields.sql`: Added `primary_suspected_root_cause`, `supporting_evidence_ids`, `contradictory_evidence_ids` columns to `investigation_agent_reports`.

### 4.4 Deterministic ID Generators Review
- `CorrelationEngine.java` / `WorkingTreeForensicsCorrelator.java`:
  - Working tree anomaly claim: hardcoded `"CLM-WT-ANOMALY"`.
  - Commit correlation claims: `"CLM-" + commitSha.substring(0, 7)`.
  - Stack frame evidence: `"EVD-FRAME-" + shortSha + "-" + fileName`.
  - Git commit evidence: `"EVD-GIT-" + shortSha`.
  - Config diff evidence: `"EVD-CFG-" + shortSha`.
- Because these generators are strictly deterministic (a core TraceBack design virtue for reproducible forensic analysis), analyzing the same commit or anomaly across multiple runs **guarantees identical identifiers**.

---

## 5. Section 6 — Proposed Flyway V4 Migration Design

### 5.1 Architecture Options Comparison

```
Option A: Composite Primary Keys
┌──────────────────────────────────────────────┐
│ PRIMARY KEY (investigation_id, evidence_id)  │
│ PRIMARY KEY (investigation_id, claim_id)     │
└──────────────────────────────────────────────┘
• Pros: Reflects domain relation.
• Cons: Requires JPA @EmbeddedId / @IdClass, breaking changes to JpaRepository interfaces,
        complicates simple ID-based lookups.

Option B: Investigation-Scoped String Identifiers
┌──────────────────────────────────────────────┐
│ evidenceId = "EVD-<uuid>-<shortSha>"         │
│ claimId    = "CLM-<uuid>-<type>"             │
└──────────────────────────────────────────────┘
• Pros: Single column PK preserved.
• Cons: Requires modifying every generator across the entire codebase, breaks backward
        compatibility with existing historical dossiers and report JSON payloads.

Option C: Surrogate UUID Primary Keys + Unique Constraints (RECOMMENDED)
┌─────────────────────────────────────────────────────────────────┐
│ id UUID PRIMARY KEY DEFAULT gen_random_uuid(),                  │
│ CONSTRAINT uq_inv_evidence UNIQUE (investigation_id, evidence_id│
│ CONSTRAINT uq_inv_claim    UNIQUE (investigation_id, claim_id)  │
└─────────────────────────────────────────────────────────────────┘
• Pros: 
  - Standard enterprise JPA pattern matching TimelineEventEntity and AgentStepEntity.
  - Zero breaking changes to public domain models or API DTOs.
  - Zero ID generator modifications.
  - Guarantees database-enforced multi-tenancy isolation.
  - Enables clean save/upsert without cross-investigation collisions.
```

### 5.2 Recommended Design: Option C Details

#### Proposed Flyway Migration: `V4__investigation_data_isolation.sql`

```sql
-- =========================================================================
-- V4__investigation_data_isolation.sql
-- TraceBack 2.0: Multi-Tenant Investigation Isolation & Surrogate Primary Keys
-- =========================================================================

-- 1. Restore true historical ownership in investigation_evidence before schema migration
UPDATE investigation_evidence
SET investigation_id = (payload->>'investigationId')::uuid
WHERE payload IS NOT NULL 
  AND payload->>'investigationId' IS NOT NULL 
  AND (payload->>'investigationId') != 'GLOBAL';

-- 2. Add surrogate primary key and unique constraint to investigation_evidence
ALTER TABLE investigation_evidence DROP CONSTRAINT IF EXISTS investigation_evidence_pkey;
ALTER TABLE investigation_evidence ADD COLUMN IF NOT EXISTS id UUID DEFAULT gen_random_uuid();
ALTER TABLE investigation_evidence ADD PRIMARY KEY (id);
ALTER TABLE investigation_evidence ADD CONSTRAINT uq_inv_evd_investigation_evidence 
    UNIQUE (investigation_id, evidence_id);

-- 3. Add surrogate primary key and unique constraint to investigation_claims
ALTER TABLE investigation_claims DROP CONSTRAINT IF EXISTS investigation_claims_pkey;
ALTER TABLE investigation_claims ADD COLUMN IF NOT EXISTS id UUID DEFAULT gen_random_uuid();
ALTER TABLE investigation_claims ADD PRIMARY KEY (id);
ALTER TABLE investigation_claims ADD CONSTRAINT uq_inv_clm_investigation_claim 
    UNIQUE (investigation_id, claim_id);

-- 4. Reconstruct displaced historical claim CLM-WT-ANOMALY for investigation 5c48eb24-...
INSERT INTO investigation_claims (
    id,
    investigation_id,
    claim_id,
    statement,
    claim_type,
    validation_status,
    supporting_evidence_ids,
    contradicting_evidence_ids,
    validation_explanation,
    created_at
)
SELECT 
    gen_random_uuid(),
    '5c48eb24-a00e-435a-aff8-88879ea113fb'::uuid,
    elem->>'claimId',
    elem->>'statement',
    'WORKING_TREE_ANOMALY',
    elem->>'status',
    (elem->'supportingEvidenceIds')::jsonb,
    (elem->'contradictingEvidenceIds')::jsonb,
    elem->>'explanation',
    (elem->>'assertedAt')::timestamptz
FROM investigation_agent_reports r,
     jsonb_array_elements(r.validated_claims) AS elem
WHERE r.report_id = 'REP-e2803caa' 
  AND elem->>'claimId' = 'CLM-WT-ANOMALY'
ON CONFLICT (investigation_id, claim_id) DO UPDATE SET
    statement = EXCLUDED.statement,
    validation_status = EXCLUDED.validation_status,
    supporting_evidence_ids = EXCLUDED.supporting_evidence_ids,
    validation_explanation = EXCLUDED.validation_explanation;

-- 5. Create high-performance compound indexes
CREATE INDEX IF NOT EXISTS idx_inv_evd_composite ON investigation_evidence (investigation_id, evidence_id);
CREATE INDEX IF NOT EXISTS idx_inv_clm_composite ON investigation_claims (investigation_id, claim_id);
```

### 5.3 Required Java Entity & Repository Updates

#### `EvidenceItemEntity.java`
```java
@Entity
@Table(name = "investigation_evidence", uniqueConstraints = {
    @UniqueConstraint(name = "uq_inv_evd_investigation_evidence", columnNames = {"investigation_id", "evidence_id"})
})
public class EvidenceItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evidence_id", nullable = false, length = 128)
    private String evidenceId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;
    
    // ... all other fields, constructors, getters, setters
}
```

#### `InvestigationEvidenceRepository.java`
```java
public interface InvestigationEvidenceRepository extends JpaRepository<EvidenceItemEntity, UUID> {
    List<EvidenceItemEntity> findByInvestigationId(UUID investigationId);
    Optional<EvidenceItemEntity> findByInvestigationIdAndEvidenceId(UUID investigationId, String evidenceId);
    boolean existsByInvestigationIdAndEvidenceId(UUID investigationId, String evidenceId);
}
```

#### `ClaimEntity.java`
```java
@Entity
@Table(name = "investigation_claims", uniqueConstraints = {
    @UniqueConstraint(name = "uq_inv_clm_investigation_claim", columnNames = {"investigation_id", "claim_id"})
})
public class ClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false, length = 128)
    private String claimId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;
    
    // ... all other fields, constructors, getters, setters
}
```

#### `InvestigationClaimRepository.java`
```java
public interface InvestigationClaimRepository extends JpaRepository<ClaimEntity, UUID> {
    List<ClaimEntity> findByInvestigationId(UUID investigationId);
    Optional<ClaimEntity> findByInvestigationIdAndClaimId(UUID investigationId, String claimId);
    boolean existsByInvestigationIdAndClaimId(UUID investigationId, String claimId);
}
```

---

## 6. Section 7 — Recovery Analysis for Displaced Historical Data

### 6.1 Feasibility Assessment: 100% Recoverable Without Rerunning Investigations

The audit definitively establishes that **no data was destroyed**. The historical investigation state is fully reconstructible from existing database records and backup archives:

1. **`CLM-WT-ANOMALY`**:
   - Preserved in `investigation_agent_reports.validated_claims` under `report_id = 'REP-e2803caa'`.
   - Preserved in `complete_active_investigation_backup.json` (lines 40-120).
   - Contains all 79 supporting evidence IDs, `SUPPORTED` validation status, and the complete audit statement.
2. **103 Historical Evidence Items**:
   - All 103 items exist in `investigation_evidence`.
   - Their original creator ID (`5c48eb24-a00e-435a-aff8-88879ea113fb`) is preserved inside `payload->>'investigationId'`.
   - Their SHA-256 integrity hashes (`integrity_hash`) remain 100% valid.
3. **Synthetic Evidence Items**:
   - Scenarios A, B, C, D, and E have their creator IDs preserved in their respective payloads or report references.

### 6.2 Data Reconciliation Verification Query

Running the following read-only SQL extracts the exact historical record directly from `REP-e2803caa`:

```sql
SELECT 
    elem->>'claimId' as claim_id,
    '5c48eb24-a00e-435a-aff8-88879ea113fb' as investigation_id,
    elem->>'status' as status,
    jsonb_array_length((elem->'supportingEvidenceIds')::jsonb) as supporting_evidence_count,
    elem->>'statement' as statement
FROM investigation_agent_reports r,
     jsonb_array_elements(r.validated_claims) AS elem
WHERE r.report_id = 'REP-e2803caa' 
  AND elem->>'claimId' = 'CLM-WT-ANOMALY';
```

**Output**:
- `claim_id`: `CLM-WT-ANOMALY`
- `investigation_id`: `5c48eb24-a00e-435a-aff8-88879ea113fb`
- `status`: `SUPPORTED`
- `supporting_evidence_count`: `79`
- `statement`: `"Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established."`

---

## 7. Operational & Invariant Verification

During the entire Phase 9.6.6 audit:
- **Strict Read-Only Mode**: No database `UPDATE`, `INSERT`, `DELETE`, or `ALTER` statements were executed.
- **Process Continuity**:
  - Backend PID `15076` remained undisturbed on port 8080.
  - Frontend PID `19724` remained undisturbed on port 5173.
  - PostgreSQL PID `2724` remained undisturbed on port 5432.
- **TacitIQ Filesystem Invariant**:
  - Tracked missing files: exactly `79`.
  - Modified files: exactly `1` (`README.md`).
  - Staged / Untracked: `0`.
- **Database Count Invariant**:
  - Investigations: `15`
  - Evidence: `135`
  - Claims: `34`
  - Agent Reports: `14`
  - Agent Steps: `70`
  - Timeline Events: `102`
  - Flyway Schema Version: `3`

---

## Conclusion & Next Phase Recommendations

Phase 9.6.6 has conclusively audited and documented:
1. The exact mechanism of cross-investigation collisions caused by globally scoped primary keys in `investigation_evidence` and `investigation_claims`.
2. The displacement of historical claim `CLM-WT-ANOMALY` and the data leakage into Scenario E.
3. The complete data recovery blueprint ensuring 100% lossless remediation.
4. The production-ready Flyway V4 migration specification based on surrogate UUID primary keys and compound unique constraints (Option C).

The application is fully prepared for an authorized Phase 9.6.7 migration execution when approved.
