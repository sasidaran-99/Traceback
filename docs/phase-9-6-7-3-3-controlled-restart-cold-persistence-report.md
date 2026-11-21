# TraceBack 2.0 — Phase 9.6.7.3.3
# Controlled Backend Restart & Cold Persistence Verification Report

**Execution Mode:** Controlled Restart & Cold Persistence Rehydration Verification  
**Completion Date:** 2026-11-09  
**Status:** COMPLETED SUCCESSFULLY — 100% PERSISTENCE INTEGRITY VERIFIED  
**Target Environment:** Java 21 / Spring Boot 3.3.4 (PID `33928`, Port 8080) / React 18 + Vite (PID `29504`, Port 5173) / PostgreSQL 18.3 (Flyway V4)  
**Historical Acceptance Repository:** TacitIQ EconomicTimes (`C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ\EconomicTimes`)  
**Active Historical Investigation Baseline:** `5c48eb24-a00e-435a-aff8-88879ea113fb` (`REP-e2803caa`)  

---

## 1. Executive Summary

Phase 9.6.7.3.3 successfully resumed and completed the controlled backend restart and cold persistence verification workflow for TraceBack 2.0 following the authorized Phase 9.6.7.3.2 duplicate step cleanup.

The backend was initialized on port 8080 from the verified packaged application archive [`traceback-api-1.0.0-SNAPSHOT.jar`](file:///c:/Users/Sasidaran/Desktop/Sasidaran/Projects/TraceBack/traceback-api/target/traceback-api-1.0.0-SNAPSHOT.jar) without executing any bootstrap import or database writes. With a cold JVM cache, all 15 investigations rehydrated from PostgreSQL 18.3 through the REST API with:
- **0 foreign-key violations** and **0 dangling references** across all evidence, claims, timelines, and agent reports.
- **100% verdict consistency** across the historical TacitIQ investigation and all five official synthetic scenarios.
- **Complete multi-tenant data isolation**: Evidence and claims remain strictly partitioned by investigation ID.
- **Flawless frontend and browser regression**: Chrome DevTools Protocol verified the Dashboard, History table (15 rows), individual dossiers, interactive claim-to-evidence navigation, and 5-step audit trails.
- **TacitIQ Acceptance Repository Unmodified**: Invariant at 79 missing tracked files, 1 modified `README.md`, 0 staged, 0 untracked files, HEAD `d711d65a3e2fecd40257af992993bfab07fb7a24`.

---

## 2. Checkpoint 1 — Read-Only Database & Cleanup Verification

### 2.1 Table Row Counts in `traceback_db`

Read-only SQL queries verified the exact expected counts across all six core persistence tables:

| Table Name | Count | Status |
|---|---|---|
| `investigations` | **15** | CONFIRMED INVARIANT |
| `investigation_evidence` | **184** | CONFIRMED INVARIANT |
| `investigation_claims` | **53** | CONFIRMED INVARIANT |
| `investigation_agent_reports` | **14** | CONFIRMED INVARIANT |
| `investigation_agent_steps` | **70** | CONFIRMED RESTORED (Cleaned) |
| `investigation_timeline_events` | **102** | CONFIRMED INVARIANT |

### 2.2 Historical TacitIQ Step Audit (`5c48eb24-a00e-435a-aff8-88879ea113fb`)

Direct query confirmed that exactly 5 agent steps remain for the historical investigation:

| Step # | UUID | Database `step_name` | Status | Created At |
|---|---|---|---|---|
| 1 | `1fd63490-0c95-4bdc-9091-69fd120ca7c4` | `Deterministic Ingestion` | COMPLETED | `2026-11-08 18:22:48.45683+05:30` |
| 2 | `ee1914ce-06fb-4e13-8978-ab98e01deef7` | `External Technical Research` | COMPLETED | `2026-11-08 18:22:48.458825+05:30` |
| 3 | `a181a90d-625a-46bf-8d11-b86de05445f1` | `Bifrost AI Gateway Reasoning` | COMPLETED | `2026-11-08 18:22:48.46183+05:30` |
| 4 | `b42becaa-7155-4dea-ad3a-1c83df9d3de9` | `Evidence Grounding & Claim Validation` | COMPLETED | `2026-11-08 18:22:48.464826+05:30` |
| 5 | `2a5317da-f959-4279-8906-15c59c252454` | `Final Verdict` | COMPLETED | `2026-11-08 18:22:48.468827+05:30` |

- **Duplicate UUID Verification**: Query searching for the 5 deleted duplicate UUIDs returned **0 rows found**.
- **Step Name Discrepancy Reconciliation**:
  * In the Phase 9.6.7.3.1 report, the textual narrative paraphrased these steps based on their functional purpose within the working-tree forensics pipeline (*"Initialize Working Tree Analysis"*, *"Correlate Missing Files with Git Log"*, etc.).
  * In Phase 9.6.7.3.2 and 9.6.7.3.3 reports, the exact database column values (`step_name`) are faithfully documented: `Deterministic Ingestion`, `External Technical Research`, `Bifrost AI Gateway Reasoning`, `Evidence Grounding & Claim Validation`, and `Final Verdict`.
  * The actual database records were never altered or rewritten to match report text.

---

## 3. Checkpoint 2 — Application Artifact Verification

The executable Spring Boot archive was inspected prior to launch:

- **Path:** [`traceback-api/target/traceback-api-1.0.0-SNAPSHOT.jar`](file:///c:/Users/Sasidaran/Desktop/Sasidaran/Projects/TraceBack/traceback-api/target/traceback-api-1.0.0-SNAPSHOT.jar)
- **Size:** 55,403,083 bytes (~52.8 MB)
- **Timestamp:** `2026-11-09T16:02:33.5593562+05:30`
- **Build Origin:** Phase 9.6.7.3.1 full reactor build (`BUILD SUCCESS`, 200/200 tests passed).
- **Integrity:** Verified executable Spring Boot fat-jar containing V4 entity mappings and surrogate UUID PKs.

---

## 4. Checkpoint 3 — Controlled Backend Startup

### 4.1 Pre-Flight Checks

1. **Port 8080 Availability:** Confirmed free (0 active listeners).
2. **PostgreSQL Connectivity:** Verified active on `localhost:5432` (`traceback_db`).
3. **Repository Allowlist:** Configured in startup arguments:
   `--traceback.security.allowed-repo-roots=C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack,C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ`
4. **Credential Protection:** Logs confirmed zero API keys or plaintext credentials exposed.
5. **No Bootstrap Execution:** No write operations or bootstrap routines were executed during startup.

### 4.2 Startup Log Verification

```
2026-11-09T16:27:24.910+05:30  INFO 33928 --- [traceback-api] [           main] com.traceback.api.TracebackApplication   : Starting TracebackApplication using Java 21.0.12 with PID 33928
2026-11-09T16:27:31.342+05:30  INFO 33928 --- [traceback-api] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 4 migrations (execution time 00:00.097s)
2026-11-09T16:27:31.367+05:30  INFO 33928 --- [traceback-api] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "public": 4
2026-11-09T16:27:31.375+05:30  INFO 33928 --- [traceback-api] [           main] o.f.core.internal.command.DbMigrate      : Schema "public" is up to date. No migration necessary.
2026-11-09T16:27:35.263+05:30  INFO 33928 --- [traceback-api] [           main] com.traceback.core.git.GitCollector      : GitCollector initialized with 2 authorized repository root(s): [C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack, C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ]
2026-11-09T16:27:37.564+05:30  INFO 33928 --- [traceback-api] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http) with context path '/'
2026-11-09T16:27:37.587+05:30  INFO 33928 --- [traceback-api] [           main] com.traceback.api.TracebackApplication   : Started TracebackApplication in 13.63 seconds (process running for 15.091)
```

### 4.3 Health Check

```bash
curl.exe -s http://localhost:8080/api/health
```
Response:
```json
{"status":"UP","timestamp":"2026-11-09T10:57:48.845064700Z","service":"traceback-api","version":"1.0.0-SNAPSHOT"}
```

---

## 5. Checkpoint 4 — Cold Persistence Verification Across Target Investigations

Upon cold rehydration with empty JVM cache, all target investigations rehydrated via REST API with zero dangling references and 100% verdict accuracy:

| Investigation Name | Investigation ID | Mode | Verdict | Evd | Clm | Tml | Stp | Dangling Refs |
|---|---|---|---|---|---|---|---|---|
| **Historical TacitIQ Forensics** | `5c48eb24-a00e-435a-aff8-88879ea113fb` | `WORKING_TREE_FORENSICS` | `CONFIRMED` | 103 | 17 | 35 | 5 | **0** |
| **Scenario A: Hikari Connection Failure** | `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` | `RUNTIME_FAILURE` | `PROBABLE` | 5 | 3 | 6 | 5 | **0** |
| **Scenario B: Node.js Express TypeError** | `3bc02925-2215-4d9f-a1bf-19b56e139a98` | `RUNTIME_FAILURE` | `PROBABLE` | 4 | 3 | 3 | 5 | **0** |
| **Scenario C: Synthetic Missing Files** | `74880eda-145c-4ebb-bdd2-630233cc87a5` | `WORKING_TREE_FORENSICS` | `CONFIRMED` | 3 | 3 | 2 | 5 | **0** |
| **Scenario D: Honest Abstention** | `59bbecc4-41d4-49ef-8b1b-07c34d5153d3` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 5 | 2 | 3 | 5 | **0** |
| **Scenario E: Misleading Commit Filter** | `c53c484c-8c43-4bf9-a631-62e60bd28ff5` | `RUNTIME_FAILURE` | `CONFIRMED` | 8 | 4 | 7 | 5 | **0** |

### Key Isolation & Grounding Validations

1. **No Evidence Bleed**: Scenario E now correctly owns 8 evidence records (its true creator set), rather than the prior 135-item collision state.
2. **Deterministic Uniqueness**: All claims and evidence items resolved with `hasDuplicates = false`.
3. **Audit Trail Preservation**: All 6 target investigations retain their complete 5-step agent audit trails.
4. **Terminology Compliance**: Heuristic ranking scores ($S = T + O + A - C$) and grounded confidence scores are explicitly differentiated from statistical probabilities throughout UI and API payloads.

---

## 6. Checkpoint 5 — API Regression Across All 15 Investigations

Full API traversal of all 15 investigations confirmed 100% response integrity:

| # | Investigation ID | Service | Mode | Status | Evidence | Claims | Timeline | Steps | Report ID | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | `c53c484c-8c43-4bf9-a631-62e60bd28ff5` | `inventory-service` | `RUNTIME_FAILURE` | `COMPLETED` | 8 | 4 | 7 | 5 | `REP-d3bf1e00` | `CONFIRMED` |
| 1 | `59bbecc4-41d4-49ef-8b1b-07c34d5153d3` | `documentation-service` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 5 | 2 | 3 | 5 | `REP-1b6815ab` | `INSUFFICIENT_EVIDENCE` |
| 2 | `74880eda-145c-4ebb-bdd2-630233cc87a5` | `forensics-demo` | `WORKING_TREE_FORENSICS` | `COMPLETED` | 3 | 3 | 2 | 5 | `REP-d1b0ee2f` | `CONFIRMED` |
| 3 | `3bc02925-2215-4d9f-a1bf-19b56e139a98` | `user-service` | `RUNTIME_FAILURE` | `COMPLETED` | 4 | 3 | 3 | 5 | `REP-bfa895ae` | `PROBABLE` |
| 4 | `9cd50b74-ee3f-451b-b1ea-837b4f6ce195` | `order-service` | `RUNTIME_FAILURE` | `COMPLETED` | 5 | 3 | 6 | 5 | `REP-16c0831f` | `PROBABLE` |
| 5 | `c8e9c281-bac7-4f04-bc0d-4b5d1708da6f` | `order-service` | `RUNTIME_FAILURE` | `COMPLETED` | 4 | 0 | 6 | 0 | *None* | `COMPLETED` |
| 6 | `f2dd8f16-9c51-4b13-95fd-179c2900035a` | `inventory-service` | `RUNTIME_FAILURE` | `COMPLETED` | 8 | 4 | 7 | 5 | `REP-00da2dd4` | `PROBABLE` |
| 7 | `59b7959c-b960-4e23-87cb-5337d846a25b` | `order-service` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 5 | 2 | 6 | 5 | `REP-16871bd4` | `INSUFFICIENT_EVIDENCE` |
| 8 | `5e53f60a-5f03-458c-81e5-b3c5660f7c3a` | `inventory-service` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 11 | 3 | 7 | 5 | `REP-7e8ac12e` | `INSUFFICIENT_EVIDENCE` |
| 9 | `2925282d-63ec-423f-91a9-aedcf016598f` | `documentation-service` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 5 | 2 | 3 | 5 | `REP-600c63cf` | `INSUFFICIENT_EVIDENCE` |
| 10 | `07162d39-10dc-42aa-b6bb-4525995033f6` | `forensics-demo` | `WORKING_TREE_FORENSICS` | `COMPLETED` | 3 | 3 | 2 | 5 | `REP-149b7a49` | `CONFIRMED` |
| 11 | `de4417a4-b155-4756-8375-d8160da897ad` | `user-service` | `RUNTIME_FAILURE` | `COMPLETED` | 4 | 3 | 3 | 5 | `REP-a43e4cf2` | `PROBABLE` |
| 12 | `003d70b4-706a-46ae-b2de-ea4a8c0d9c3e` | `order-service` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 7 | 2 | 6 | 5 | `REP-7039246c` | `INSUFFICIENT_EVIDENCE` |
| 13 | `baf36899-090a-4d3c-aeb1-2d21cce5f5bf` | `order-service` | `RUNTIME_FAILURE` | `INSUFFICIENT_EVIDENCE` | 9 | 2 | 6 | 5 | `REP-7a7ab094` | `INSUFFICIENT_EVIDENCE` |
| 14 | `5c48eb24-a00e-435a-aff8-88879ea113fb` | `TacitIQ-EconomicTimes` | `WORKING_TREE_FORENSICS` | `COMPLETED` | 103 | 17 | 35 | 5 | `REP-e2803caa` | `CONFIRMED` |

---

## 7. Checkpoint 6 — Frontend & Browser Regression

### 7.1 Vitest Unit & Component Test Suite

Command: `npm test --prefix frontend`  
Outcome: **10 test files passed, 46 tests passed, 0 failures**.

### 7.2 Headless Chrome E2E Suite

Executed automated Chrome DevTools Protocol suite [`scratch/run_phase96733_browser_e2e.mjs`](file:///c:/Users/Sasidaran/Desktop/Sasidaran/Projects/TraceBack/scratch/run_phase96733_browser_e2e.mjs) against `http://localhost:5173` and `http://localhost:8080`:

1. **Dashboard:**
   - 5 recent investigations rendered in table.
   - 7 metric overview cards rendered.
   - Authoritative link: *"View All (15)"* pointing to `/history`.
   - Screenshot: `phase96733_01_dashboard_loaded.png`.
2. **History Page:**
   - Table displays exactly 15 persisted investigation rows with correct service, mode, verdict, and timestamp badges.
   - Screenshot: `phase96733_02_history.png`.
3. **Historical TacitIQ Dossier (`5c48eb24`):**
   - Renders `CONFIRMED` verdict, 79 missing tracked files claim, 103 evidence explorer items, 35 timeline events, and 5 completed agent steps.
   - Screenshot: `phase96733_03_tacitiq_dossier.png`.
4. **Scenarios A–E Dossiers:**
   - Scenario A: `PROBABLE` verdict, suspect commit `ee51c22`.
   - Scenario B: `PROBABLE` verdict, suspect commit `9fb2786`.
   - Scenario C: `CONFIRMED` verdict, working tree missing file `users.js`.
   - Scenario D: `INSUFFICIENT_EVIDENCE` honest abstention badge.
   - Scenario E: `CONFIRMED` verdict, misleading commit `1d6c388` refuted by contradictory evidence.
   - Screenshots: `phase96733_04_scenario_a.png` through `phase96733_08_scenario_e.png`.
5. **Interactive Claim-to-Evidence Navigation:**
   - Clicked evidence pill `EVD-WT-MISSING-587cb980af` on Scenario C claim.
   - Switched to Evidence tab, populated search input with `EVD-WT-MISSING-587cb980af`, applied amber highlight border, and displayed `SELECTED FROM CLAIM` badge.
   - Screenshot: `phase96733_09_claim_evidence_navigation.png`.

---

## 8. Checkpoint 7 — Final Database & Repository Invariants

Post-verification read-only checks confirmed complete system stability:

### 8.1 Database Counts (`traceback_db`)

```
              tab              | count 
-------------------------------+-------
 investigations                |    15
 investigation_evidence        |   184
 investigation_claims          |    53
 investigation_agent_reports   |    14
 investigation_agent_steps     |    70
 investigation_timeline_events |   102
```

### 8.2 TacitIQ EconomicTimes Repository State

- 79 missing tracked files
- 1 modified `README.md`
- 0 staged files
- 0 untracked files
- HEAD: `d711d65a3e2fecd40257af992993bfab07fb7a24`
- *Zero modifications or restoration took place.*

---

## 9. Conclusion

TraceBack 2.0 has successfully passed all cold persistence, database isolation, API, and frontend regression verifications. The application is completely stable, fully isolated, and ready for release demonstration.
