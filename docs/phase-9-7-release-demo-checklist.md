# TraceBack 2.0 — Hackathon Demonstration Operational Checklist & Runbook

**Document Version:** 2.0.0 (Phase 9.7 Release Edition)  
**System Target:** Live Hackathon Presentation, Judge Evaluation, & Fail-Safe Operations  
**Scope:** Pre-Flight Verification, Live Presentation Protocol, and Emergency Recovery Runbook

---

## 1. Pre-Flight Verification Checklist (T-30 Minutes)

Execute the following checklist prior to commencing the hackathon presentation. Every step must be verified green before taking the stage.

### 1.1 Infrastructure & Services State

- [ ] **PostgreSQL Service (Port 5432):**
  - Verify PostgreSQL process is running (PID 2724 or active service).
  - Test connection:
    ```bash
    psql -U traceback_user -d traceback_db -c "SELECT 1;"
    ```
- [ ] **Spring Boot Backend (Port 8080):**
  - Verify process is running (PID 33928 or active task).
  - Verify health endpoint:
    ```bash
    curl -s http://localhost:8080/api/health
    ```
    Expected output: `{"status":"UP","service":"traceback-api","version":"1.0.0-SNAPSHOT"}`
- [ ] **Vite Frontend Dev Server (Port 5173):**
  - Verify frontend server is running (PID 29504 or active task).
  - Verify HTTP response:
    ```bash
    curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/
    ```
    Expected output: `200`
  - Open `http://localhost:5173/` in a browser and verify the dashboard renders without white-screen or console errors.

---

### 1.2 Database Telemetry & Inventory Integrity

Connect to `traceback_db` and confirm exact table row counts:

```sql
SELECT 'investigations' as table_name, count(*) FROM investigations
UNION ALL SELECT 'investigation_evidence', count(*) FROM investigation_evidence
UNION ALL SELECT 'investigation_claims', count(*) FROM investigation_claims
UNION ALL SELECT 'investigation_agent_reports', count(*) FROM investigation_agent_reports
UNION ALL SELECT 'investigation_agent_steps', count(*) FROM investigation_agent_steps
UNION ALL SELECT 'investigation_timeline_events', count(*) FROM investigation_timeline_events;
```

**Expected Baseline Verification Table:**

| Table Name | Expected Row Count | Verified Status |
|:---|:---:|:---:|
| `investigations` | **15** | [ ] |
| `investigation_evidence` | **184** | [ ] |
| `investigation_claims` | **53** | [ ] |
| `investigation_agent_reports` | **14** *(1 legacy investigation without report)* | [ ] |
| `investigation_agent_steps` | **70** *(14 completed investigations × 5 steps)* | [ ] |
| `investigation_timeline_events` | **102** | [ ] |

---

### 1.3 Key Dossier Navigation Readiness

Verify the 6 primary demonstration dossiers load in browser tabs or via direct links:

- [ ] **Historical TacitIQ Forensics:**  
  URL: `http://localhost:5173/investigations/5c48eb24-a00e-435a-aff8-88879ea113fb`  
  *Check:* 103 Evidence items, 17 Claims, 35 Timeline events, Verdict: `CONFIRMED`.
- [ ] **Scenario A (Hikari Pool Failure):**  
  URL: `http://localhost:5173/investigations/9cd50b74-ee3f-451b-b1ea-837b4f6ce195`  
  *Check:* Verdict: `PROBABLE`, Suspect: `ee51c22`, Confidence: `71%`.
- [ ] **Scenario B (Node.js TypeError):**  
  URL: `http://localhost:5173/investigations/3bc02925-2215-4d9f-a1bf-19b56e139a98`  
  *Check:* Verdict: `PROBABLE`, Suspect: `9fb2786`, Confidence: `71%`.
- [ ] **Scenario C (Working-Tree Missing Files):**  
  URL: `http://localhost:5173/investigations/74880eda-145c-4ebb-bdd2-630233cc87a5`  
  *Check:* Verdict: `CONFIRMED`, Primary cause: Anomaly detected (process not established).
- [ ] **Scenario D (Honest Abstention):**  
  URL: `http://localhost:5173/investigations/59bbecc4-41d4-49ef-8b1b-07c34d5153d3`  
  *Check:* Verdict: `INSUFFICIENT_EVIDENCE`, Primary cause: None / Telemetry insufficient.
- [ ] **Scenario E (Anti-Recency Bias):**  
  URL: `http://localhost:5173/investigations/c53c484c-8c43-4bf9-a631-62e60bd28ff5`  
  *Check:* Verdict: `CONFIRMED`, Suspect: `c881289`, Confidence: `76%`.

---

### 1.4 Environment & Presentation Hygiene

- [ ] **TacitIQ Working Tree Integrity:**  
  Run `git -C "C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ\EconomicTimes" status --short`.  
  *Verify:* Exactly 80 status entries (1 modified `README.md`, 79 missing Java files). **Do not run `git restore` or `git checkout`!**
- [ ] **Secret Hygiene:** Verify that no terminal windows, configuration files, or browser views display plaintext API tokens (`BIFROST_API_KEY`, `TAVILY_API_KEY`).
- [ ] **Screen & Browser Configuration:**
  - Browser resolution: 1920×1080 (100% or 110% zoom for projector legibility).
  - Open Chrome Developer Tools: Verify console is clean with 0 unhandled exceptions.
  - Close background chat applications (Slack, Discord, Email notifications) to prevent popups during judging.
- [ ] **Fallback Asset Preparation:**  
  Verify local fallback screenshots exist in `docs/screenshots/`:
  - `phase96733_dashboard.png`
  - `phase96733_history.png`
  - `phase96733_tacitiq_dossier.png`
  - `phase96733_scenario_a_dossier.png`
  - `phase96733_scenario_d_insufficient.png`
  - `phase96733_claim_navigation.png`

---

## 2. Live Presentation Execution Protocol

Follow these strict operational rules while presenting:

1. **Strictly Follow the 8–10 Minute Demo Script:**  
   Stick to the timed breakdown in [phase-9-7-hackathon-demo-script.md](file:///c:/Users/Sasidaran/Desktop/Sasidaran/Projects/TraceBack/docs/phase-9-7-hackathon-demo-script.md). Do not improvise ad-hoc detours.
2. **Do Not Trigger Untested Live Investigations on Stage:**  
   Demonstrate the rich persisted dossiers already validated in `traceback_db`. Running unvetted repository paths live risks network hiccups or path errors during your strict 10-minute window.
3. **Never Describe Scores as Statistical Probabilities:**  
   When showing `71%` or `76%`, use the exact term: **"heuristic ranking score"** or **"grounded report confidence"**. Explain that it orders competing hypotheses via $S = T + O + A - C$.
4. **Emphasize the Insufficient Evidence Verdict (Scenario D):**  
   Spend a full minute demonstrating honest abstention. Judges value an agent that refuses to fabricate answers when data is missing.
5. **Demonstrate Interactive Claim Navigation:**  
   Click a supporting evidence ID (e.g. `EVD-WT-MISSING-...`) in the Claims tab of TacitIQ to show the UI automatically transitioning to the Evidence Explorer with green highlighting. This physically demonstrates claim-to-evidence grounding.

---

## 3. Emergency Recovery Runbook

If any component experiences a glitch immediately before or during the evaluation, follow the corresponding recovery procedure.

> **IMPORTANT:** Do NOT execute these recovery procedures during normal operations. Use them ONLY if a live outage occurs.

### Recovery Scenario 1: Backend API Unavailable (HTTP 502 / Connection Refused)

**Symptom:** Browser shows network errors connecting to `localhost:8080/api/...`.  
**Diagnostic:**
```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```
**Recovery Steps:**
1. Check if backend process crashed:
   ```powershell
   Get-Process -Name java -ErrorAction SilentlyContinue
   ```
2. If stopped, start backend using pre-packaged JAR:
   ```powershell
   java -jar traceback-api/target/traceback-api-1.0.0-SNAPSHOT.jar --traceback.security.allowed-repo-roots="C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack,C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TacitIQ"
   ```
3. Wait 8 seconds, then verify health: `curl http://localhost:8080/api/health`.

---

### Recovery Scenario 2: Frontend Dev Server Unavailable (Port 5173 Unresponsive)

**Symptom:** Browser cannot reach `localhost:5173`.  
**Recovery Steps:**
1. Check if Vite process is alive on port 5173:
   ```powershell
   Get-NetTCPConnection -LocalPort 5173 -ErrorAction SilentlyContinue
   ```
2. If stopped, restart Vite server:
   ```powershell
   cmd.exe /c "npm run dev --prefix frontend"
   ```
3. Refresh browser at `http://localhost:5173/`.

---

### Recovery Scenario 3: PostgreSQL Database Unavailable (Port 5432 Down)

**Symptom:** Backend health logs show `Connection to localhost:5432 refused`.  
**Recovery Steps:**
1. Check PostgreSQL Windows service:
   ```powershell
   Get-Service -Name postgresql*
   ```
2. Restart PostgreSQL service:
   ```powershell
   Start-Service -Name postgresql-x64-18
   ```
3. Verify connection:
   ```powershell
   psql -U traceback_user -d traceback_db -c "SELECT 1;"
   ```

---

### Recovery Scenario 4: Empty Dashboard / Database Missing Investigations

**Symptom:** Dashboard shows 0 investigations.  
**Diagnostic:** Check row count in `traceback_db`:
```powershell
psql -U traceback_user -d traceback_db -c "SELECT count(*) FROM investigations;"
```
**Recovery Steps:**
1. If database was accidentally cleared, re-bootstrap using the verified atomic backup:
   ```powershell
   curl -X POST "http://localhost:8080/api/admin/bootstrap-active-investigation?force=true"
   ```
2. Verify row counts return to 15 investigations and 184 evidence items.

---

### Recovery Scenario 5: External Bifrost or Tavily API Outage During Live Call

**Symptom:** Live agent run takes longer than 15 seconds or returns HTTP 504.  
**Mitigation:**
1. TraceBack's built-in fallback triggers automatically: `DefaultBifrostClient` catches the timeout/exception, logs the error, and delegates to the deterministic fallback reasoning engine.
2. In the presentation, highlight this graceful degradation: *"Notice that even if the external model gateway is unreachable, TraceBack's deterministic foundation generates a complete, validated dossier without failing."*

---

### Recovery Scenario 6: Total Network / Projector Catastrophic Failure

**Symptom:** Wi-Fi cuts out, localhost services are disrupted, or local machine freezes.  
**Emergency Fallback:**
1. Open the fallback visual dossier folder: `docs/screenshots/`.
2. Present the pre-rendered, high-resolution screenshots capturing the entire 8-minute demonstration sequence.
3. Every screen (Dashboard, Scenarios A–E, TacitIQ Forensics, Claim Navigation) is preserved with high-fidelity visual evidence.
