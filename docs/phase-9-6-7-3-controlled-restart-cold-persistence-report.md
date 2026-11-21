# TRACEBACK 2.0 — PHASE 9.6.7.3: CONTROLLED BACKEND RESTART & COLD PERSISTENCE REPORT

**Execution Date:** 2026-11-09  
**Execution Type:** Authorized Controlled Restart + Cold Verification  
**Database Name:** `traceback_db` (PostgreSQL 18.3, Port 5432, PID 2724)  
**Schema Version:** Flyway V4  
**Audit Status:** STOPPED AT CHECKPOINT 3 — MAVEN INTEGRATION TEST FAILURE ON LIVE DATABASE  

---

## 1. Executive Summary

Phase 9.6.7.3 was initiated under explicit user authorization to execute a controlled backend restart and cold persistence verification of TraceBack 2.0 following the successful Phase 9.6.7 Gate B data isolation remediation and Phase 9.6.7.2 audit.

In accordance with strict safety directives:
> *"If Maven fails, stop and report the error. Do not modify source code automatically."*  
> *"If any safety check fails, STOP immediately and report the exact failure. Never improvise destructive database repairs."*

During Checkpoint 3 (Controlled Backend Stop and Build), the full Maven reactor clean build (`mvn clean package`) was launched using Java 21.0.12. Modules `traceback-parent`, `traceback-core`, and `traceback-ingestion` compiled and passed all tests cleanly. However, in `traceback-api`, the integration test `PostgreSqlActiveInvestigationBootstrapTest` executed against the live active database (`traceback_db` on port 5432) and triggered an assertion failure:
```text
Expected size: 5 but was: 10 in: [AgentStepEntity ... x10]
```
The test failed because `ActiveInvestigationBootstrapService.importFromBackup` with `force=true` appended 5 new agent step rows to the pre-existing 5 agent steps for investigation `5c48eb24-a00e-435a-aff8-88879ea113fb`, increasing the step count to 10 (and total global steps in `traceback_db` from 70 to 75).

Because Maven exited with code 1 before the packaging phase of `traceback-api`, the runnable application JAR was not generated. In compliance with strict safety rules, execution was halted immediately without modifying source code or improvising database deletions.

---

## 2. Pre-Restart Baseline & Process Inventory (Checkpoint 1)

Prior to initiating build actions, the environment baseline was captured:

| Component | Target Port | Status at Baseline | Notes |
|---|---|---|---|
| PostgreSQL 18.3 | `5432` | **ACTIVE** (PID 2724) | `PostgreSQL 18.3 on x86_64-windows, compiled by msvc-19.44.35223, 64-bit` |
| TraceBack Backend | `8080` | **INACTIVE** | Dormant following previous turn environment restart. Port free. |
| TraceBack Frontend | `5173` | **INACTIVE** | Dormant following previous turn environment restart. Port free. |
| Active Database | `traceback_db` | **HEALTHY** | Flyway V4 active (`installed_rank=4`, checksum `1917839775`, `success=true`) |

### Database Baseline Table Counts (Pre-Build)

- `investigations`: **15**
- `investigation_evidence`: **184**
- `investigation_claims`: **53**
- `investigation_agent_reports`: **14**
- `investigation_agent_steps`: **70**
- `investigation_timeline_events`: **102**

---

## 3. Project Configuration & Path Audit (Checkpoint 2)

An audit of project configuration confirmed:

1. **Frontend Directory**: Confirmed to be `frontend/` (not `traceback-frontend`). Vite dev server is configured for port 5173 with proxy `/api` targeting `http://localhost:8080`.
2. **Health Endpoint**:
   - `HealthController.java` is explicitly mapped to `@RequestMapping("/api/health")`, returning `{"status":"UP","timestamp":...}`.
   - Actuator `/actuator/health` is also exposed in `application.yml`.
3. **Repository Allowlist**:
   - Bound via `@Value("${traceback.security.allowed-repo-roots:}")` in `GitCollector.java` and `GitWorkingTreeCollector.java`.
   - Parsing logic (`config.split("[,;]")`) safely splits comma-separated Windows paths:
     - Root 1: `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack`
     - Root 2: `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ`
4. **Bifrost and Tavily**: Configured with fallback defaults; no secrets exposed.

---

## 4. Build and Test Execution (Checkpoint 3)

### Maven Reactor Execution Details

- **Command**: `mvn clean package`
- **Java Runtime**: `21.0.12, Oracle Corporation, C:\Program Files\Java\jdk-21.0.12`
- **Maven Version**: `3.9.14 (C:\maven\apache-maven-3.9.14)`
- **Build Exit Code**: `1` (BUILD FAILURE)

### Module Execution Summary

| Reactor Module | Artifact Type | Source Files | Test Files | Tests Run | Passed | Skipped | Failures | Status | Duration |
|---|---|---|---|---|---|---|---|---|---|
| `traceback-parent` | POM | — | — | — | — | — | — | **SUCCESS** | 0.319 s |
| `traceback-core` | JAR | 89 | 29 | 140 | 139 | 1 | 0 | **SUCCESS** | 24.884 s |
| `traceback-ingestion` | JAR | 2 | 1 | 2 | 2 | 0 | 0 | **SUCCESS** | 5.619 s |
| `traceback-api` | JAR | 22 | 14 | 53 | 52 | 0 | 1 | **FAILURE** | 38.231 s |
| **REACTOR TOTAL** | | **113** | **44** | **195** | **193** | **1** | **1** | **FAILED** | 1:10 min |

### Detailed Test Failure Breakdown

The single test failure occurred in:
- **Class**: `com.traceback.api.integration.PostgreSqlActiveInvestigationBootstrapTest`
- **Test Method**: `shouldBootstrapActiveInvestigationIntoPostgreSql()` (Line 102)
- **Failure Type**: `java.lang.AssertionError`
- **Message**:
  ```text
  Expected size: 5 but was: 10 in:
  [com.traceback.core.domain.AgentStepEntity@91deb194,
   com.traceback.core.domain.AgentStepEntity@af2ac495,
   com.traceback.core.domain.AgentStepEntity@81871fd1,
   com.traceback.core.domain.AgentStepEntity@6699fc9f,
   com.traceback.core.domain.AgentStepEntity@ae9e124d,
   com.traceback.core.domain.AgentStepEntity@84b93147,
   com.traceback.core.domain.AgentStepEntity@c088e70d,
   com.traceback.core.domain.AgentStepEntity@b7d98049,
   com.traceback.core.domain.AgentStepEntity@c6296451,
   com.traceback.core.domain.AgentStepEntity@be014914]
  ```

---

## 5. Root Cause Analysis

### Mechanism of the Test Failure

1. **Test Environment Coupling**:
   `PostgreSqlActiveInvestigationBootstrapTest.java` is annotated with `@ActiveProfiles("postgres-test")`. In `traceback-api/src/test/resources/application-postgres-test.yml`, the datasource points directly to the live development database:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:traceback_db}
   ```
2. **Force Re-Import Call**:
   The test calls `bootstrapService.importFromBackup(BACKUP_PATH, true)` with `force = true`.
3. **Absence of Duplicate Protection on AgentStepEntity**:
   In `ActiveInvestigationBootstrapService.java` (lines 378–385):
   ```java
   if (agentStepRepository != null) {
       AgentStepEntity entity = new AgentStepEntity(
               UUID.randomUUID(), invId, stepNum, stepName, "COMPLETED", desc,
               objectMapper.writeValueAsString(meta), 0L
       );
       agentStepRepository.save(entity);
   }
   ```
   Unlike `EvidenceItemEntity` and `ClaimEntity` (which have compound unique constraints and update-in-place logic), `AgentStepEntity` does not check for pre-existing steps for `(investigation_id, step_number)` and does not remove existing steps before re-inserting during a forced re-import.
4. **Resulting Side-Effect**:
   Because `traceback_db` already contained the 5 original steps for TacitIQ investigation `5c48eb24-a00e-435a-aff8-88879ea113fb`, the forced import appended 5 new rows. The test assertion `assertThat(steps).hasSize(5)` then encountered 10 rows and failed.
5. **Lifecycle Interruption**:
   Because the test failed, Maven halted reactor execution before the `package` phase of `traceback-api`. As a result, no new runnable JAR was emitted to `traceback-api/target/`.

---

## 6. Current Database State (`traceback_db`)

Direct read-only inspection of `traceback_db` following the failed Maven run confirms:

| Table | Count Before Build | Count After Build | Delta | Analysis |
|---|---|---|---|---|
| `investigations` | 15 | 15 | 0 | Untouched |
| `investigation_evidence` | 184 | 184 | 0 | Untouched (idempotent compound key) |
| `investigation_claims` | 53 | 53 | 0 | Untouched (idempotent compound key) |
| `investigation_agent_reports` | 14 | 14 | 0 | Untouched (updated in-place) |
| `investigation_agent_steps` | 70 | **75** | **+5** | 5 duplicated steps inserted for `5c48eb24` |
| `investigation_timeline_events` | 102 | 102 | 0 | Untouched (idempotent lookup) |

### Specific Duplicated Step Records in `investigation_agent_steps`

The 5 duplicate rows inserted at `2026-11-09 15:27:55` are:
1. `a83a8124-d25f-4eed-807d-00de55320b61` (Step 1: Deterministic Ingestion)
2. `403d61da-32ae-4858-9c59-8e4f88535b4d` (Step 2: External Technical Research)
3. `e6265254-e935-4528-8225-e8f3098fcea7` (Step 3: Bifrost AI Gateway Reasoning)
4. `2005398c-1261-441a-9537-2d0b67dbb673` (Step 4: Evidence Grounding & Claim Validation)
5. `f1f60f3f-2539-4caf-9d2c-2ca1f7e227c4` (Step 5: Final Verdict)

The original 5 step rows (created at `2026-11-08 18:22:48`) remain intact:
1. `1fd63490-0c95-4bdc-9091-69fd120ca7c4`
2. `ee1914ce-06fb-4e13-8978-ab98e01deef7`
3. `a181a90d-625a-46bf-8d11-b86de05445f1`
4. `b42becaa-7155-4dea-ad3a-1c83df9d3de9`
5. `2a5317da-f959-4279-8906-15c59c252454`

---

## 7. TacitIQ Repository Non-Interference Verification (Checkpoint 8)

Read-only inspection via `git status --porcelain` on `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ\EconomicTimes`:
- Exactly 79 missing tracked files (` D ...`)
- Exactly 1 unstaged modified file (` M README.md`)
- 0 staged files
- 0 untracked files
- HEAD Commit: `d711d65a3e2fecd40257af992993bfab07fb7a24`

The external repository remains 100% invariant and undisturbed.

---

## 8. Failures, Limitations, and Outstanding Risks

1. **Integration Test Live Database Coupling**:
   The `postgres-test` profile runs integration tests against the live `traceback_db`. Any test executing mutative operations (such as forced bootstrap imports) modifies the live active database.
2. **Missing De-duplication in `ActiveInvestigationBootstrapService` for Agent Steps**:
   Unlike evidence and claims, `AgentStepEntity` lacks a compound unique constraint on `(investigation_id, step_number)`. Repeated calls to `importFromBackup(..., true)` append steps rather than updating them.
3. **Packaging Interruption**:
   Because `mvn clean package` failed at the test phase of `traceback-api`, no runnable JAR exists in `traceback-api/target/` to launch the backend for Checkpoints 4–7.

---

## 9. Final Gate Decision & Recommended Next Steps

### Gate Decision

> [!WARNING]
> ### **DECISION: STOPPED AT CHECKPOINT 3 — AWAITING USER DIRECTION**
> A test failure occurred during Maven reactor execution on a live database integration test. In strict adherence to Safety Rule 12 and Checkpoint 3 instructions, no automatic source code modifications or unapproved database deletions have been performed. Execution is paused.

### Action Options for User Approval

To resume and successfully complete Phase 9.6.7.3, three options are available:

1. **Option A (Targeted Step Cleanup + Package without Test Mutation)**:
   - Execute a safe, targeted SQL deletion removing only the 5 duplicate steps inserted at `15:27:55` (`DELETE FROM investigation_agent_steps WHERE id IN ('a83a8124-...', '403d61da-...', 'e6265254-...', '2005398c-...', 'f1f60f3f-...')`), restoring `investigation_agent_steps` to exactly 70 rows.
   - Run `mvn package -DskipTests` to package `traceback-api-1.0.0-SNAPSHOT.jar` (since the 194 passing tests have already been verified in Phase 9.6.7.2).
   - Proceed through Checkpoints 4–8 (Launch backend, verify cold rehydration, launch frontend, run browser E2E).

2. **Option B (Restore Pre-Migration Baseline Dump + Package without Tests)**:
   - Restore the active database from the verified backup `traceback_db_backup_2026-11-08T18-12-55-988Z.dump` and re-apply Flyway V4 via `restore_and_remigrate_active_db.mjs` (the exact proven procedure from Gate B).
   - Package with `mvn package -DskipTests`.
   - Proceed with cold rehydration.

3. **Option C (Fix `ActiveInvestigationBootstrapService` Idempotency in Code)**:
   - Modify `ActiveInvestigationBootstrapService.java` to delete existing steps for `invId` when `force=true` before inserting new ones.
   - Re-run `mvn clean package`.
