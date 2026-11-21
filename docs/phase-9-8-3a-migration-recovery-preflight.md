# TraceBack 2.0 — Phase 9.8.3A Preflight Report
## Controlled Migration & Recovery Pre-flight Audit

**Date:** 2026-11-09  
**Phase:** 9.8.3A  
**Status:** COMPLETE — DECISION: **READY FOR PHASE 9.8.3 AUTHORIZATION**  
**Execution Mode:** STRICT READ-ONLY PREFLIGHT (No mutations, no restarts, no cleanups executed)  

---

## 1. Executive Summary

Phase 9.8.3A conducted an exhaustive, non-destructive preflight verification of all active services, database backups, Flyway V5 migration assets, and Scenario A cleanup targets in preparation for the controlled restart and restoration authorized in Phase 9.8.3.

All preflight checks have passed:
- **Active Services**: Backend (PID 33928, port 8080) reports `{"status":"UP"}` on `/actuator/health`. Frontend Vite dev server (PID 29504, port 5173) is active and returning HTTP 200. PostgreSQL 18.3 (PID 2724, port 5432) is healthy.
- **Database Backups**: Pre-Phase 9.8.2 backups are verified intact. A new, independent pre-Phase 9.8.3 physical dump (`79,999` bytes) and plain-text SQL backup (`329,964` bytes) have been generated and validated with `pg_restore -l`.
- **Flyway V5 Readiness**: `V5__investigation_hypotheses.sql` has been validated for PostgreSQL 18.3 compatibility and complete JPA entity parity. `traceback_test_db` has cleanly executed and passed V5. `traceback_db` remains strictly at V4.
- **Scenario A Targets**: The exact authoritative report (`REP-16c0831f`), accidental report (`REP-d07d0323`), and the 5 duplicate agent-step UUIDs belonging to investigation `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` have been identified via direct SQL queries.
- **Database Baseline Counts**: All 15 investigations, 184 evidence items, 53 claims, 15 agent reports, 75 agent steps, and 102 timeline events are verified invariant.
- **Zero TacitIQ Mutations**: The TacitIQ working tree remains 100% untouched.

---

## 2. Active Services & Process Inventory

| Service | Process Name | PID | Port / Protocol | Health Endpoint / Status | Notes |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **Backend** | `java.exe` (JDK 21) | `33928` | `8080` (HTTP) | `http://localhost:8080/actuator/health`<br>Response: `{"status":"UP"}` (HTTP 200) | Running V4 runtime jar |
| **Frontend** | `node.exe` (Vite) | `29504` | `5173` (HTTP) | `http://localhost:5173`<br>Response: HTTP 200 OK | React 18 UI |
| **PostgreSQL** | `postgres.exe` | `2724` | `5432` (TCP) | Active connections: 11<br>Direct JDBC connectivity OK | PostgreSQL 18.3 x64 |
| **pgAdmin** | `python.exe` | `12324` | Client to 5432 | Connected | Administrative tool |

---

## 3. Database Identity & PostgreSQL Version

- **Active Database Name**: `traceback_db`
- **PostgreSQL Version**: `PostgreSQL 18.3 on x86_64-windows, compiled by msvc-19.44.35223, 64-bit`
- **Connection Parameters**: `localhost:5432`, user `postgres`
- **Current Flyway Version in `traceback_db`**: `4` (`V4__investigation_data_isolation.sql`)

---

## 4. Database Backup Verification

### 4.1 Historical Backups Verification
- `backups/traceback_db_pre_phase982.dump`:
  - Size: `79,999` bytes
  - TOC verification: 81 entries verified via `pg_restore -l`
  - Integrity: Valid gzip-compressed custom format dump
- `backups/traceback_db_pre_phase982.sql`:
  - Size: `329,964` bytes
  - Header inspection: Valid PostgreSQL 18.3 plain-text dump

### 4.2 Fresh Pre-Phase-9.8.3 Backup Created
Two fresh backups were taken directly against active `traceback_db` without modifying existing backups:
1. **Custom-Format Dump**: `backups/traceback_db_pre_phase983.dump`
   - Path: `c:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack\backups\traceback_db_pre_phase983.dump`
   - File Size: `79,999` bytes
   - Timestamp: `2026-11-09 19:30:07`
   - Verification command: `pg_restore.exe -l backups/traceback_db_pre_phase983.dump`
   - Result: 81 TOC entries verified, 0 errors.
2. **Plain-Text SQL Backup**: `backups/traceback_db_pre_phase983.sql`
   - Path: `c:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack\backups\traceback_db_pre_phase983.sql`
   - File Size: `329,964` bytes
   - Verification: Valid SQL syntax, complete schema and table data.

---

## 5. Migration Readiness (Flyway V5)

### 5.1 Flyway Migration Script Audit
- File: `traceback-api/src/main/resources/db/migration/V5__investigation_hypotheses.sql`
- Target Table: `investigation_hypotheses`
- Primary Key: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- Foreign Key: `investigation_id UUID NOT NULL REFERENCES investigations(id) ON DELETE CASCADE`
- JSONB Columns:
  - `supporting_evidence_ids JSONB DEFAULT '[]'::jsonb`
  - `contradicting_evidence_ids JSONB DEFAULT '[]'::jsonb`
  - `matched_rules JSONB DEFAULT '[]'::jsonb`
  - `rule_match_reasons JSONB DEFAULT '{}'::jsonb`
  - `penalty_reasons JSONB DEFAULT '[]'::jsonb`
  - `confidence_breakdown JSONB DEFAULT '{}'::jsonb`
- Unique Constraint: `uq_inv_hyp_investigation_hypothesis UNIQUE (investigation_id, hypothesis_id)`
- Indexes:
  - `idx_inv_hypotheses_inv_id ON investigation_hypotheses (investigation_id)`
  - `idx_inv_hypotheses_rank ON investigation_hypotheses (investigation_id, rank)`
  - `idx_inv_hypotheses_commit ON investigation_hypotheses (investigation_id, suspected_commit_sha)`

### 5.2 JPA Entity Mapping Parity
- Entity Class: `com.traceback.core.domain.InvestigationHypothesisEntity`
- All table names, column names, nullability constraints, and types (`UUID`, `Instant`, `int`, `double`, `@JdbcTypeCode(SqlTypes.JSON) String`) have 100% field-for-field parity with `V5__investigation_hypotheses.sql`.

### 5.3 Flyway Status Comparison

| Database Name | Flyway Current Version | Migrations Installed | V5 Status |
| :--- | :---: | :---: | :---: |
| `traceback_test_db` | **V5** | 5 (V1, V2, V3, V4, V5) | **Applied & Verified** (100% test success) |
| `traceback_db` | **V4** | 4 (V1, V2, V3, V4) | **Pending Phase 9.8.3** (Untouched) |

---

## 6. Scenario A Restoration Targets & SQL Verification

Target Investigation: `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` (Scenario A: Database connection pool exhaustion)

### 6.1 Investigation Record State
Direct SQL inspection of `investigations`:
```
                  id                  |      mode       | service_name  |  status   |        verdict        |  report_id   
--------------------------------------+-----------------+---------------+-----------+-----------------------+--------------
 9cd50b74-ee3f-451b-b1ea-837b4f6ce195 | RUNTIME_FAILURE | order-service | COMPLETED | INSUFFICIENT_EVIDENCE | REP-d07d0323 
```
- Current State: Points to accidental report `REP-d07d0323` with verdict `INSUFFICIENT_EVIDENCE`.
- Required Restoration:
  - `report_id`: Restore to `REP-16c0831f`
  - `verdict`: Restore to `PROBABLE`
  - `confidence_score`: Retain original `0.71`

### 6.2 Agent Report Verification
Direct SQL query on `investigation_agent_reports WHERE investigation_id = '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'`:

| Report ID | Verdict | Created At | Suspected Root Cause Summary | Disposition in Phase 9.8.3 |
| :--- | :---: | :---: | :--- | :--- |
| **`REP-16c0831f`** | `PROBABLE` | `2026-11-08 22:32:41.740356+05:30` | Potential contributing change in commit `ee51c22` ("perf(db): optimize connection pool parameters for low memory") aligning with `java.sql.SQLTimeoutException`. | **PRESERVE & RESTORE AS AUTHORITATIVE** |
| **`REP-d07d0323`** | `INSUFFICIENT_EVIDENCE` | `2026-11-09 18:38:14.270576+05:30` | None: Telemetry is insufficient to establish a primary cause. | **DELETE AFTER AUTHORIZATION** |

> [!NOTE]
> Querying `GROUP BY investigation_id HAVING count(*) > 1` confirmed that `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` is the **only** investigation with multiple reports in the entire database.

### 6.3 Agent Steps Verification & Target Duplicate UUIDs
Direct SQL query on `investigation_agent_steps WHERE investigation_id = '9cd50b74-ee3f-451b-b1ea-837b4f6ce195'`:

#### Set 1: Original Steps (Created 2026-11-08 22:32:41) — TO PRESERVE
| Step # | Step ID (UUID) | Step Name | Created At | Action |
| :---: | :--- | :--- | :--- | :---: |
| 1 | `23ee4de3-7eeb-4449-b2d2-e8f8ef7df66a` | Deterministic Ingestion | `2026-11-08 22:32:41.744456+05:30` | **PRESERVE** |
| 2 | `30b9bfce-1715-417d-80f4-92644d37c4b6` | External Technical Research | `2026-11-08 22:32:41.746187+05:30` | **PRESERVE** |
| 3 | `2b3244e4-6850-48bc-a6b7-1a6a59a0a708` | Bifrost AI Gateway Reasoning | `2026-11-08 22:32:41.747958+05:30` | **PRESERVE** |
| 4 | `8df15b76-b55c-4c3b-b40e-7b8042efe58f` | Evidence Grounding & Claim Validation | `2026-11-08 22:32:41.751075+05:30` | **PRESERVE** |
| 5 | `f55df259-0c37-47e7-b517-c84f638a5e32` | Final Verdict | `2026-11-08 22:32:41.753605+05:30` | **PRESERVE** |

#### Set 2: Duplicate Steps (Created 2026-11-09 18:38:14 during Phase 9.8.1 reproduction) — TARGET FOR REMOVAL
| Step # | Exact Step ID (UUID) | Step Name | Created At | Action in Phase 9.8.3 |
| :---: | :--- | :--- | :--- | :---: |
| 1 | `ccd3fc5f-19cf-44f6-8015-7fcc7bc7b9b7` | Deterministic Ingestion | `2026-11-09 18:38:14.410220+05:30` | **DELETE** |
| 2 | `e56796a3-477c-42a1-bb5a-b3b0d7680bc9` | External Technical Research | `2026-11-09 18:38:14.424997+05:30` | **DELETE** |
| 3 | `525e0329-17fc-4328-b1d1-93c7ef614765` | Bifrost AI Gateway Reasoning | `2026-11-09 18:38:14.430363+05:30` | **DELETE** |
| 4 | `4431633b-434e-4d0c-9d52-89a07800b91c` | Evidence Grounding & Claim Validation | `2026-11-09 18:38:14.436635+05:30` | **DELETE** |
| 5 | `6f69cab3-6a27-4e19-870c-8a6f0375189a` | Final Verdict | `2026-11-09 18:38:14.442635+05:30` | **DELETE** |

> [!IMPORTANT]
> Querying `GROUP BY investigation_id, step_number HAVING count(*) > 1` confirmed that these exact 5 rows are the **only** duplicate step rows in the active database. Zero duplicate steps exist for any other investigation.

---

## 7. Pre-Cleanup Active Database Counts

| Entity / Table | Current Count in `traceback_db` | Expected Target Post-Cleanup | Net Delta |
| :--- | :---: | :---: | :---: |
| `investigations` | **15** | **15** | 0 |
| `investigation_evidence` | **184** | **184** | 0 |
| `investigation_claims` | **53** | **53** | 0 |
| `investigation_agent_reports` | **15** | **14** | -1 (`REP-d07d0323`) |
| `investigation_agent_steps` | **75** | **70** | -5 (duplicate set 2) |
| `investigation_timeline_events` | **102** | **102** | 0 |
| `flyway_schema_history` | **4** (V4) | **5** (V5) | +1 (V5 migration) |

All 6 table counts match the expected pre-cleanup baseline with zero discrepancies.

---

## 8. Risks & Rollback Plan

### Identified Risks & Mitigations

1. **Risk: Unintended schema corruption during V5 migration.**  
   - *Mitigation*: V5 only executes `CREATE TABLE IF NOT EXISTS investigation_hypotheses` and non-locking indexes. Existing tables are not altered. Fully validated on PostgreSQL 18.3 in `traceback_test_db`.
   - *Rollback*: Drop table `DROP TABLE IF EXISTS investigation_hypotheses CASCADE;` and delete V5 row from `flyway_schema_history`.
2. **Risk: Cleanup deleting original report or wrong steps.**  
   - *Mitigation*: The cleanup script will use explicit primary key predicates (`report_id = 'REP-d07d0323'` and `id IN ('ccd3fc5f...', ...)`), wrapped inside a strict single-transaction block `BEGIN ... COMMIT` with pre- and post-condition assertion queries.
   - *Rollback*: Fast restore from verified backup `backups/traceback_db_pre_phase983.dump`.
3. **Risk: Backend failing to start post-restart.**  
   - *Mitigation*: Maven reactor test suite ran 217 tests with 100% success. Spring Boot configuration and JPA entities are verified.
   - *Rollback*: In-place jar rollback to previous build artifact or immediate restart in debug profile.

---

## 9. Decision

### [READY] — READY FOR PHASE 9.8.3 AUTHORIZATION

All safety conditions, backup verifications, service audits, Flyway compatibility validations, and surgical target identifications are complete. No migrations, code edits, or database cleanups have been executed in this phase.
