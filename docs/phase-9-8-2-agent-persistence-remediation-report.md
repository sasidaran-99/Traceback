# TraceBack 2.0 — Phase 9.8.2 Remediation Report
## Agent Idempotency, Persistence & Rehydration Remediation

**Date:** 2026-11-09  
**Phase:** 9.8.2  
**Status:** COMPLETED — 100% SUCCESS  
**Database Isolation Target:** `traceback_test_db` (for test suite); `traceback_db` (active, strictly protected)  

---

## 1. Executive Summary

Phase 9.8.2 resolves the root causes identified in the Phase 9.8.1 audit where re-running or looking up an existing investigation via the Agent workflow triggered an unintended full re-investigation, generated duplicate agent-step records, overwrote the authoritative report pointer, and yielded an `INSUFFICIENT_EVIDENCE` verdict due to missing stack-frame error recognition and unpersisted hypotheses.

All five core architectural fixes and two complementary repository and API enhancements have been designed, implemented, and verified with zero side effects:

1. **Safe Default Agent Idempotency**: `POST /api/investigations/agent` accepts `forceRerun` (default `false`). When called with an existing `investigationId` and `forceRerun=false`, it returns the existing authoritative report in $\mathcal{O}(1)$ time without re-running Git or log analysis.
2. **Report Repository Integrity**: Ambiguous `Optional<AgentReportEntity> findByInvestigationId(UUID)` was replaced with ordered list queries and explicit lookup by `reportId`. Historical report records are fully preserved; no destructive unique constraints were applied.
3. **Stack-Frame Error Evidence Recognition**: `InvestigationPersistenceMapper` now recognizes `EvidenceType.STACK_FRAME` as valid runtime error evidence alongside `EvidenceType.STACK_TRACE`, ensuring rehydration accurately records $\text{errorsDetected} \ge 1$ and extracts primary exception details.
4. **Hypothesis Persistence & Rehydration (Flyway V5)**: Created Flyway migration `V5__investigation_hypotheses.sql` and corresponding JPA entity/repository. Hypotheses, their suspect commit hashes, heuristic score breakdowns ($S = T + O + A - C$), supporting/contradicting evidence IDs, and validation states are persisted and rehydrated. Legacy records without hypotheses safely default to empty collections without data fabrication.
5. **Deterministic Agent-Step Idempotency**: Replaced random UUID generation for agent steps with deterministic RFC 4122 v3 UUIDs (`UUID.nameUUIDFromBytes`) scoped to the investigation ID and step number. Re-running with `forceRerun=true` updates existing step records in place transactionally rather than creating duplicates.
6. **Strict Database Isolation**: All automated integration tests executed exclusively against `traceback_test_db`. Active database `traceback_db` was pre-backed up and remained strictly invariant throughout the phase.
7. **Zero TacitIQ Mutations**: The TacitIQ repository was inspected and remained 100% untouched.

---

## 2. Pre-Remediation Active Database State & Backup

Prior to executing any tests or schema updates, a complete physical and logical backup of the active PostgreSQL database (`traceback_db`) was taken and verified:

- **Custom-format dump**: `backups/traceback_db_pre_phase982.dump` (79,999 bytes)
- **Plain-text SQL dump**: `backups/traceback_db_pre_phase982.sql` (329,964 bytes)

### Active Database Verification (`traceback_db`)

| Table Name | Pre-Phase 9.8.2 Count | Post-Phase 9.8.2 Count | Delta | Status |
| :--- | :---: | :---: | :---: | :---: |
| `investigations` | 15 | 15 | 0 | INVARIANT |
| `investigation_evidence` | 184 | 184 | 0 | INVARIANT |
| `investigation_claims` | 53 | 53 | 0 | INVARIANT |
| `investigation_agent_reports` | 15 | 15 | 0 | INVARIANT |
| `investigation_agent_steps` | 75 | 75 | 0 | INVARIANT |
| `investigation_timeline_events` | 102 | 102 | 0 | INVARIANT |
| `flyway_schema_history` | V4 | V4 | 0 | INVARIANT |

> [!NOTE]
> As explicitly instructed, **database cleanup was excluded from Phase 9.8.2**. The duplicate agent steps and `REP-d07d0323` created during the Phase 9.8.1 reproduction remain preserved in `traceback_db`. Cleanup and restoration of Scenario A's authoritative report pointer (`REP-16c0831f`) will occur under separately authorized Phase 9.8.3.

---

## 3. Detailed Root Cause Analysis & Implemented Remedies

### Defect 1: Safe Default Agent Idempotency
- **Root Cause**: `POST /api/investigations/agent` accepted `investigationId` in `AgentInvestigationRequest` but always executed `investigationAgent.investigate(...)`.
- **Remediation**:
  - Added `Boolean forceRerun` to `AgentInvestigationRequest` (default `false`).
  - Added backwards-compatible constructors for existing callers and serialization.
  - In `AgentInvestigationController`, when `request.investigationId() != null && !request.isForceRerun()`, the controller retrieves the existing report via `investigationAgent.getReport(request.investigationId())` and returns HTTP 200 without invoking the Git or log analyzer.
  - Added `GET /api/investigations/{id}/agent-reports` and `GET /api/investigations/agent/{id}/history` to inspect the full report lineage for any investigation.

### Defect 2: Report Repository Non-Unique Result & Authoritative Selection
- **Root Cause**: `InvestigationAgentReportRepository` declared `Optional<AgentReportEntity> findByInvestigationId(UUID)`. When multiple reports existed for an investigation (such as after a forced re-run), Spring Data JPA threw `NonUniqueResultException` or returned an arbitrary row.
- **Remediation**:
  - Replaced ambiguous method with:
    - `List<AgentReportEntity> findByInvestigationId(UUID investigationId)`
    - `List<AgentReportEntity> findByInvestigationIdOrderByCreatedAtDesc(UUID investigationId)`
    - `default Optional<AgentReportEntity> findLatestByInvestigationId(UUID investigationId)`
  - Updated `InvestigationAgent.getReport(UUID investigationId)` to look up the authoritative report ID stored in `investigations.report_id` via `findByReportId(String reportId)`. If `report_id` is null, it falls back safely to `findLatestByInvestigationId(investigationId)`.
  - Did NOT introduce a database UNIQUE constraint on `investigation_id`, preserving audit history and report versioning.

### Defect 3: EvidenceType.STACK_FRAME Rehydration Error Count
- **Root Cause**: In `InvestigationPersistenceMapper.toDomain(...)`, `errorsDetected` was calculated solely by counting evidence items where `evidence.getType() == EvidenceType.STACK_TRACE`. In TacitIQ Scenario A, parsed evidence items were typed `EvidenceType.STACK_FRAME`. Consequently, rehydrated investigations reported `errorsDetected = 0`. During correlation, the engine evaluated candidate commits against 0 errors, resulting in `INSUFFICIENT_EVIDENCE`.
- **Remediation**:
  - Updated `InvestigationPersistenceMapper` to recognize `EvidenceType.STACK_FRAME` as well as `EvidenceType.STACK_TRACE`:
    ```java
    boolean hasErrors = domainEvidence.stream()
            .anyMatch(e -> e.getType() == EvidenceType.STACK_TRACE || e.getType() == EvidenceType.STACK_FRAME);
    int errorsDetected = hasErrors ? 1 : 0;
    ```
  - Added fallback extraction of `primaryException` from `STACK_FRAME` evidence (`frame.className + ": " + frame.methodName`).

### Defect 4: Hypotheses Persistence & Rehydration
- **Root Cause**: Hypotheses were generated exclusively in-memory inside `CorrelationEngine.correlate()`. Neither Flyway V1–V4 schemas nor `InvestigationPersistenceMapper` possessed any table or mapping for `Hypothesis`, causing hypotheses to vanish upon database rehydration.
- **Remediation**:
  - Created Flyway migration `V5__investigation_hypotheses.sql` creating table `investigation_hypotheses` with:
    - Primary Key: `id UUID`
    - Foreign Key: `investigation_id UUID REFERENCES investigations(id) ON DELETE CASCADE`
    - Columns: `description`, `suspect_commit_sha`, `heuristic_score`, `temporal_score`, `ownership_score`, `author_experience_score`, `counter_evidence_score`, `validation_state`, `created_at`
    - JSONB Columns: `confidence_breakdown`, `supporting_evidence_ids`, `contradicting_evidence_ids`, `matched_rules`
    - Index: `idx_hypotheses_investigation_id`
  - Created JPA entity `InvestigationHypothesisEntity` and repository `InvestigationHypothesisRepository`.
  - Updated `InvestigationPersistenceMapper` to map `Hypothesis` to/from `InvestigationHypothesisEntity`, restoring full heuristic breakdowns ($S = T + O + A - C$), commit associations, and validation states.
  - Legacy records without hypotheses safely rehydrate to empty lists without fabricating text.
  - `InvestigationService.persistInvestigation` saves all hypotheses transactionally.

### Defect 5: Deterministic Agent-Step Idempotency
- **Root Cause**: `InvestigationAgent` created steps with `UUID.randomUUID()` and called `stepRepository.saveAll(stepEntities)`. Every re-run created 5 new rows.
- **Remediation**:
  - In `InvestigationPersistenceMapper.toEntity(AgentInvestigationStep step, UUID investigationId, UUID existingId)`:
    ```java
    UUID stepId = existingId != null ? existingId :
        UUID.nameUUIDFromBytes((investigationId + ":step:" + step.stepNumber()).getBytes(StandardCharsets.UTF_8));
    ```
  - Added `Optional<AgentStepEntity> findByInvestigationIdAndStepNumber(UUID, int)` to `InvestigationAgentStepRepository`.
  - Added `@Transactional public void persistAgentSteps(UUID investigationId, List<AgentInvestigationStep> steps)` to `InvestigationAgent`: reuses existing row IDs by step number, updates them in place, and trims trailing steps if a run produced fewer steps.

---

## 4. Code & Migration Modifications

### Modified Files:
- `traceback-api/src/main/java/com/traceback/api/dto/AgentInvestigationRequest.java`
- `traceback-api/src/main/java/com/traceback/api/controller/AgentInvestigationController.java`
- `traceback-core/src/main/java/com/traceback/core/repository/InvestigationAgentReportRepository.java`
- `traceback-core/src/main/java/com/traceback/core/repository/InvestigationAgentStepRepository.java`
- `traceback-core/src/main/java/com/traceback/core/agent/InvestigationAgent.java`
- `traceback-core/src/main/java/com/traceback/core/persistence/InvestigationPersistenceMapper.java`
- `traceback-core/src/main/java/com/traceback/core/investigation/InvestigationService.java`
- `traceback-api/src/test/java/com/traceback/api/migration/FlywayMigrationTest.java`
- `traceback-api/src/test/java/com/traceback/api/integration/PostgreSql16IntegrationTest.java`
- `traceback-api/src/test/java/com/traceback/api/integration/Phase921PersistenceRehydrationIntegrationTest.java`

### Newly Created Files:
- `traceback-api/src/main/resources/db/migration/V5__investigation_hypotheses.sql`
- `traceback-core/src/main/java/com/traceback/core/domain/InvestigationHypothesisEntity.java`
- `traceback-core/src/main/java/com/traceback/core/repository/InvestigationHypothesisRepository.java`
- `traceback-core/src/test/java/com/traceback/core/agent/Phase982AgentIdempotencyAndPersistenceTest.java`
- `traceback-api/src/test/java/com/traceback/api/controller/AgentInvestigationIdempotencyApiTest.java`
- `backups/traceback_db_pre_phase982.dump`
- `backups/traceback_db_pre_phase982.sql`

---

## 5. Automated Verification & Test Results

All unit and integration tests were executed across the Maven multi-module reactor and frontend test runner.

### Backend Test Suite Summary

```
-------------------------------------------------------
 T E S T S   S U M M A R Y
-------------------------------------------------------
 Module: traceback-core
 Tests run: 157, Failures: 0, Errors: 0, Skipped: 0
 Result: SUCCESS

 Module: traceback-ingestion
 Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
 Result: SUCCESS

 Module: traceback-api
 Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
 Result: SUCCESS
-------------------------------------------------------
 TOTAL BACKEND TESTS: 217 PASSED, 0 FAILURES, 0 ERRORS
 BUILD SUCCESS (100%)
-------------------------------------------------------
```

### Specific Phase 9.8.2 Test Coverage Highlights:
1. **`Phase982AgentIdempotencyAndPersistenceTest` (9 tests)**:
   - `testSafeDefaultIdempotencyReturnsAuthoritativeReport`: Verifies `forceRerun=false` returns authoritative report without agent execution.
   - `testReportRepositoryMultiReportHandling`: Verifies `findByReportId` selects the exact authoritative report even with multiple historical reports.
   - `testAgentStepIdempotencyInPlaceUpdate`: Verifies running twice updates existing 5 steps without creating additional rows.
   - `testDeterministicStepUuidGeneration`: Verifies RFC 4122 v3 name-UUID generation consistency.
   - `testStackFrameEvidenceSetsErrorsDetected`: Verifies `EvidenceType.STACK_FRAME` results in `errorsDetected = 1`.
   - `testHypothesisPersistenceAndRehydration`: Verifies full round-trip persistence of hypothesis with score breakdown $S = T + O + A - C$.
   - `testLegacyInvestigationWithoutHypothesesSafe`: Verifies null or empty hypothesis lists rehydrate safely to empty collections.
   - `testAuthoritativeReportFallbackToLatestWhenNull`: Verifies fallback to latest report when `investigations.report_id` is null.
   - `testForceRerunPrunesTrailingStepsIfStepCountDecreases`: Verifies step list trimming when re-run produces fewer steps.
2. **`InvestigationPersistenceMapperTest` (4 new tests)**:
   - Verifies hypothesis entity mapping, confidence breakdown JSON parsing, and evidence ID list serialization.
3. **`AgentInvestigationIdempotencyApiTest` (3 tests)**:
   - `shouldReturnAuthoritativeReportWithoutRerunningWhenForceRerunIsFalse`: MockMvc test proving HTTP 200 and authoritative report returned without agent execution.
   - `shouldRerunInvestigationWhenForceRerunIsTrue`: MockMvc test proving re-execution occurs when `forceRerun: true`.
   - `shouldReturnReportHistoryForInvestigation`: Proves `GET /api/investigations/{id}/agent-reports` returns full report history.
4. **`PostgreSql16IntegrationTest` & `Phase921PersistenceRehydrationIntegrationTest`**:
   - Proves Flyway V5 migration executes cleanly on real PostgreSQL 18.3 (`traceback_test_db`) and verifies hypothesis persistence with live JSONB columns.

### Frontend Test Suite Summary
```
 ✓ src/test/SummaryCard.test.tsx (4 tests)
 ✓ src/test/verdictAggregation.test.tsx (5 tests)
 ✓ src/test/NewInvestigationPage.test.tsx (6 tests)
 ✓ src/test/forensicPresentation.test.tsx (6 tests)
 ✓ src/test/ClaimValidationTable.test.tsx (5 tests)
 ✓ src/test/phase963NavigationAndVerdicts.test.tsx (7 tests)
 ...
 Test Files  10 passed (10)
      Tests  46 passed (46)
   Duration  2.41s
```

---

## 6. TacitIQ Repository Protection Verification

The TacitIQ repository at `C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ\EconomicTimes` was verified via `git status`:
- Zero commits created.
- Zero files added or removed.
- Working tree remains in its exact baseline state from previous phases.

---

## 7. Next Steps: Phase 9.8.3 Readiness

Phase 9.8.2 is complete and all code changes are verified. The application is now ready for **Phase 9.8.3: Controlled Restart & Scenario A Cleanup**, which will require explicit authorization to:
1. Perform a controlled shutdown of backend PID 33928.
2. Package and start the backend with Flyway V5 applied to `traceback_db`.
3. Execute authorized cleanup of Scenario A in `traceback_db`:
   - Delete report `REP-d07d0323`.
   - Restore `investigations.report_id = 'REP-16c0831f'` and `investigations.verdict = 'PROBABLE'`.
   - Remove the 5 duplicate agent steps created during Phase 9.8.1 reproduction.
4. Execute cold PostgreSQL rehydration verification across all 15 investigations.
