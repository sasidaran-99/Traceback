# TraceBack 2.0 — Phase 9.6.7.3.1
# Bootstrap Idempotency Fix & Integration Test Isolation Report

**Execution Mode:** Controlled Code Remediation & Read-Only Active Database Verification  
**Completion Date:** 2026-11-09  
**Status:** COMPLETE — AWAITING SEPARATE CLEANUP AUTHORIZATION  
**Database Target:** `traceback_db` (Active / Untouched) & `traceback_test_db` (Isolated Disposable Test DB)  
**Flyway Version:** V4  

---

## 1. Executive Summary

During Phase 9.6.7.3 Checkpoint 3 (`mvn clean package`), `PostgreSqlActiveInvestigationBootstrapTest` failed because it executed an un-isolated forced bootstrap import against the active database `traceback_db`. This increased the row count of `investigation_agent_steps` from 70 to 75 (+5 duplicate rows for the historical TacitIQ investigation `5c48eb24-a00e-435a-aff8-88879ea113fb`).

In accordance with strict safety protocols, execution of Phase 9.6.7.3 was stopped immediately with the backend stopped, and Phase 9.6.7.3.1 was initiated to:
1. Precisely inspect and document the 5 duplicate rows without mutating `traceback_db`.
2. Implement idempotent bootstrap import logic with deterministic UUID generation and scoped step replacement.
3. Establish 100% database isolation for all integration tests via a dedicated disposable database (`traceback_test_db`) and runtime fail-fast guards.
4. Verify complete Maven reactor build health without skipping tests.
5. Confirm that the active database `traceback_db` and TacitIQ repository remained 100% invariant throughout remediation.

All objectives have been successfully met. The complete test suite of 200 tests across all 4 Maven modules passes with zero failures, zero errors, and zero warnings. `mvn package` successfully produces the deployable artifact `traceback-api-1.0.0-SNAPSHOT.jar`.

---

## 2. Checkpoint 1 — Active Database Inspection & Duplicate Row Audit

### 2.1 Table Row Counts in `traceback_db`

A read-only reconciliation of all six core persistence tables in `traceback_db` verified the following state:

| Table Name | Pre-Phase 9.6.7.3 Baseline | Post-Test Failure Baseline | Current State | Invariant? |
|---|---|---|---|---|
| `investigations` | 15 | 15 | 15 | YES |
| `investigation_evidence` | 184 | 184 | 184 | YES |
| `investigation_claims` | 53 | 53 | 53 | YES |
| `investigation_agent_reports` | 14 | 14 | 14 | YES |
| `investigation_agent_steps` | 70 | 75 (+5) | 75 | YES (Preserved) |
| `investigation_timeline_events` | 102 | 102 | 102 | YES |

### 2.2 Forensic Inspection of `investigation_agent_steps`

Querying `investigation_agent_steps` for investigation `5c48eb24-a00e-435a-aff8-88879ea113fb` confirmed exactly 10 rows:

#### Original 5 Rows (Preserved)
- `1fd63490-0c95-4bdc-9091-69fd120ca7c4` | Step 1: Initialize Working Tree Analysis | Created: `2026-11-08 18:22:48.45683+05:30`
- `ee1914ce-06fb-4e13-8978-ab98e01deef7` | Step 2: Correlate Missing Files with Git Log | Created: `2026-11-08 18:22:48.458825+05:30`
- `a181a90d-625a-46bf-8d11-b86de05445f1` | Step 3: Analyze Subsystem Impact | Created: `2026-11-08 18:22:48.46183+05:30`
- `b42becaa-7155-4dea-ad3a-1c83df9d3de9` | Step 4: Validate Working Tree Claims | Created: `2026-11-08 18:22:48.464826+05:30`
- `2a5317da-f959-4279-8906-15c59c252454` | Step 5: Synthesize Working Tree Forensic Verdict | Created: `2026-11-08 18:22:48.468827+05:30`

#### Duplicate 5 Rows Identified for Separate Authorized Removal
- `a83a8124-d25f-4eed-807d-00de55320b61` | Step 1: Initialize Working Tree Analysis | Created: `2026-11-09 15:27:55.393113+05:30`
- `403d61da-32ae-4858-9c59-8e4f88535b4d` | Step 2: Correlate Missing Files with Git Log | Created: `2026-11-09 15:27:55.395789+05:30`
- `e6265254-e935-4528-8225-e8f3098fcea7` | Step 3: Analyze Subsystem Impact | Created: `2026-11-09 15:27:55.39779+05:30`
- `2005398c-1261-441a-9537-2d0b67dbb673` | Step 4: Validate Working Tree Claims | Created: `2026-11-09 15:27:55.399779+05:30`
- `f1f60f3f-2539-4caf-9d2c-2ca1f7e227c4` | Step 5: Synthesize Working Tree Forensic Verdict | Created: `2026-11-09 15:27:55.401781+05:30`

Under strict safety constraints, no delete or update operations were performed against `traceback_db`.

---

## 3. Checkpoint 2 — Idempotent Bootstrap Service Implementation

### 3.1 Root Cause Analysis

In `ActiveInvestigationBootstrapService.java`:
1. **Lack of Investigation-Scoped Step De-duplication**: When forced re-import was executed (`force=true`), the service did not delete or de-duplicate existing steps for the targeted investigation before persisting the imported steps.
2. **Random UUID Generation**: `AgentStepEntity` IDs were generated via `UUID.randomUUID()`. Each execution generated new primary keys, preventing database upsert or unique constraint collisions from catching the duplication.

### 3.2 Implemented Remediation

File: `traceback-core/src/main/java/com/traceback/core/persistence/ActiveInvestigationBootstrapService.java`

1. **Deterministic UUID Generation**:
   Replaced `UUID.randomUUID()` with RFC 4122 type 3 deterministic UUID generation based on `(investigation_id + "-step-" + step_number)`:
   ```java
   UUID deterministicStepId = UUID.nameUUIDFromBytes(
       (invId + "-step-" + stepNum).getBytes(StandardCharsets.UTF_8)
   );
   AgentStepEntity stepEntity = new AgentStepEntity(
       deterministicStepId, invId, stepNum, stepName, status, rationale, findingsJson, duration
   );
   ```

2. **Scoped Step Cleanup on Forced Re-import**:
   Prior to persisting newly parsed steps, any existing steps for the specific investigation are removed and flushed:
   ```java
   if (agentStepRepository != null) {
       List<AgentStepEntity> existingSteps = agentStepRepository.findByInvestigationIdOrderByStepNumberAsc(invId);
       if (!existingSteps.isEmpty()) {
           agentStepRepository.deleteAll(existingSteps);
           agentStepRepository.flush();
       }
   }
   ```

3. **Claim Upsert Preservation**:
   Maintained scoped lookup `claimRepository.findByInvestigationIdAndClaimId(invId, cId)` and preserved entity surrogate ID upon re-import.

---

## 4. Checkpoint 3 — Bootstrap Idempotency Unit Test Suite

A dedicated unit test class was created in `traceback-core`:  
`traceback-core/src/test/java/com/traceback/core/persistence/ActiveInvestigationBootstrapIdempotencyTest.java`

### Test Results

| Test Method | Description | Result |
|---|---|---|
| `firstImport_persistsExactCounts` | Verifies initial import persists 103 evidence, 17 claims, 35 timeline events, and 5 steps | PASSED |
| `secondImportWithoutForce_skipsDuplicate` | Verifies that a duplicate import without `force=true` skips execution and leaves counts unchanged | PASSED |
| `forcedRepeatedImport_replacesStepsWithoutDuplication` | Verifies that repeated forced imports replace steps cleanly without multiplication (remains exactly 5) | PASSED |
| `preservesUnrelatedInvestigations` | Verifies that bootstrap actions on one investigation never mutate or delete steps from other investigations | PASSED |

Command: `mvn test -pl traceback-core -Dtest=ActiveInvestigationBootstrapIdempotencyTest`  
Outcome: **4 tests run, 4 passed, 0 failures, 0 errors**.

---

## 5. Checkpoint 4 — Integration Test Database Isolation

### 5.1 Infrastructure Remediation

1. **Disposable Test Database**:
   Created dedicated database `traceback_test_db` on PostgreSQL port 5432:
   ```sql
   CREATE DATABASE traceback_test_db OWNER traceback_user;
   ```
   Restored the baseline schema and applied Flyway V4 migration.

2. **Test Configuration Isolation**:
   Updated `traceback-api/src/test/resources/application-postgres-test.yml`:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_TEST_NAME:traceback_test_db}
       username: ${DB_USER:traceback_user}
       password: ${DB_PASSWORD:traceback_password}
   ```

3. **Fail-Fast Runtime Safety Guards**:
   Implemented `@BeforeEach` safety interceptors across all four PostgreSQL integration test suites:
   - `com.traceback.api.integration.PostgreSqlActiveInvestigationBootstrapTest`
   - `com.traceback.api.migration.FlywayMigrationTest`
   - `com.traceback.api.integration.PostgreSql16IntegrationTest`
   - `com.traceback.api.persistence.Phase921PersistenceRehydrationIntegrationTest`

   Guard implementation:
   ```java
   @BeforeEach
   void verifyDatabaseIsolation() {
       String dbUrl = environment.getProperty("spring.datasource.url", "");
       if (dbUrl.toLowerCase().contains("traceback_db")) {
           throw new IllegalStateException(
               "CRITICAL SAFETY VIOLATION: Integration tests must NEVER connect to production/active database 'traceback_db'! Current URL=" + dbUrl
           );
       }
       assertThat(dbUrl).contains("traceback_test_db");
   }
   ```

4. **Multi-Module Dependency Synchronization**:
   Installed updated `traceback-core` with surrogate UUID PK mappings into the local `.m2` repository so that `traceback-api` resolves the V4 entity classes without schema mismatches.

---

## 6. Checkpoint 5 — Full Test Suite Execution

The complete Maven reactor build test suite was executed across all modules:
```bash
mvn test
```

### Module Breakdown

| Module | Scope | Tests Run | Failures | Errors | Skipped | Status |
|---|---|---|---|---|---|---|
| `traceback-parent` | Root POM | 0 | 0 | 0 | 0 | SUCCESS |
| `traceback-core` | Core engine & unit tests | 144 | 0 | 0 | 0 | SUCCESS |
| `traceback-ingestion`| Ingestion services | 2 | 0 | 0 | 0 | SUCCESS |
| `traceback-api` | Controllers, Flyway, & Postgres integration | 54 | 0 | 0 | 0 | SUCCESS |
| **TOTAL** | **All modules** | **200** | **0** | **0** | **0** | **100% PASS** |

Total execution time: 56.4 seconds.

---

## 7. Checkpoint 6 — Active Database & TacitIQ Verification

### 7.1 Database Verification (`traceback_db`)

Direct query post-test run confirmed zero mutation to the active database:
```
              tab              | count 
-------------------------------+-------
 investigations                |    15
 investigation_evidence        |   184
 investigation_claims          |    53
 investigation_agent_reports   |    14
 investigation_agent_steps     |    75
 investigation_timeline_events |   102
```

### 7.2 TacitIQ EconomicTimes Repository State

`git status --short` in `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ\EconomicTimes` confirmed the exact same 80-line working tree state:
- 1 modified file: `README.md`
- 79 deleted tracked files
- 0 untracked files
- 0 staged files

---

## 8. Checkpoint 7 — Application Package Build

Executed `mvn package` from the project root. All 200 tests executed successfully against `traceback_test_db`, and the Spring Boot repackaged executable archive was generated:
```
[INFO] Replacing main artifact C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack\traceback-api\target\traceback-api-1.0.0-SNAPSHOT.jar with repackaged archive
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for Traceback Parent 1.0.0-SNAPSHOT:
[INFO] Traceback Parent ................................... SUCCESS [  0.006 s]
[INFO] Traceback Core ..................................... SUCCESS [ 14.351 s]
[INFO] Traceback Ingestion ................................ SUCCESS [  4.283 s]
[INFO] Traceback API ...................................... SUCCESS [ 41.195 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

---

## 9. Next Steps (Awaiting User Authorization)

Phase 9.6.7.3.1 has fully accomplished its scope. To proceed:
1. **Authorized Cleanup Operation**:
   Execute a single targeted SQL command against `traceback_db` to remove the 5 duplicate agent step rows from Phase 9.6.7.3:
   ```sql
   DELETE FROM investigation_agent_steps
   WHERE id IN (
       'a83a8124-d25f-4eed-807d-00de55320b61',
       '403d61da-32ae-4858-9c59-8e4f88535b4d',
       'e6265254-e935-4528-8225-e8f3098fcea7',
       '2005398c-1261-441a-9537-2d0b67dbb673',
       'f1f60f3f-2539-4caf-9d2c-2ca1f7e227c4'
   );
   ```
   This will cleanly restore `investigation_agent_steps` from 75 to the expected 70.
2. **Resume Phase 9.6.7.3**:
   Launch the backend cleanly on port 8080 and proceed with Checkpoints 4–8 (API rehydration, cold restart verification, browser E2E regression, and final signoff).
