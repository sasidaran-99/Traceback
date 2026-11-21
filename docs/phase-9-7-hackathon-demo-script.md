# TraceBack 2.0 — Hackathon Demonstration Script (8–10 Minutes)
**Target Track:** Bifrost / AI Agents & Technical Investigations  
**Supporting Integration:** Tavily Technical Research  
**Environment:** Frontend (`http://localhost:5173`), Backend (`http://localhost:8080`), PostgreSQL (`localhost:5432`)

---

## 1. Executive Demonstration Overview

TraceBack 2.0 is an autonomous software failure investigation platform that solves a pervasive developer challenge:
> **"Don't just tell developers that something broke. Investigate what happened, explain why, and show the evidence."**

Instead of forcing on-call engineers to manually cross-reference disconnected logs, terminal stack traces, Git blame logs, and configuration files, TraceBack collects deterministic telemetry, extracts concrete evidence items, cross-examines hypotheses, leverages **Bifrost** as an AI gateway for structured reasoning and **Tavily** for external documentation research, and deterministically validates all claims before rendering an authoritative root-cause dossier.

### Timing & Sequence Matrix

| Part | Title | Target Scenario / Route | Duration | Key Message |
|:---:|:---|:---|:---:|:---|
| **1** | The Problem | Dashboard (`/`) | 1:00 | Incident triage is fractured across logs, Git, and configs. |
| **2** | Architecture Pipeline | Architecture overview / UI Header | 1:00 | Deterministic collectors + Evidence Registry + Bifrost/Tavily + Claim Validator. |
| **3** | Scenario A: Hikari DB Failure | `/investigations/9cd50b74-ee3f-451b-b1ea-837b4f6ce195` | 2:00 | Suspect commit ranking, config diff matching, `PROBABLE` verdict rationale. |
| **4** | Scenario B: Cross-Language Node.js | `/investigations/3bc02925-2215-4d9f-a1bf-19b56e139a98` | 1:00 | Language-agnostic parser adapting Node.js V8 call stacks with Git diffs. |
| **5** | Scenario C & TacitIQ: Working-Tree Forensics | `/investigations/74880eda-145c-4ebb-bdd2-630233cc87a5` & `5c48eb24-...` | 1:30 | Missing tracked files detected without Git commits (`CONFIRMED` anomaly vs process). |
| **6** | Scenario D: Honest Abstention | `/investigations/59bbecc4-41d4-49ef-8b1b-07c34d5153d3` | 1:00 | System refuses to hallucinate a culprit when telemetry is absent (`INSUFFICIENT_EVIDENCE`). |
| **7** | Scenario E: Anti-Recency Bias | `/investigations/c53c484c-8c43-4bf9-a631-62e60bd28ff5` | 1:00 | Ignores recent cosmetic commits to isolate older impactful configuration changes. |
| **8** | Closing & Judge Q&A | Navigation to History (`/history`) | 1:00 | Evidence grounding, deterministic validation, honest AI orchestration. |

---

## Part 1 — The Problem: Incident Triage Fragmentation (1:00)

### Action
1. Open browser at `http://localhost:5173/`.
2. Ensure the top stats bar and "Recent Investigations" table are visible.

### Presenter Talking Points
> "When a microservice crashes in production, what does an engineer actually do?
> 
> They pull open Datadog or CloudWatch to copy a stack trace. They open GitHub to inspect recent pull requests. They inspect Kubernetes ConfigMaps or YAML diffs to see if connection pool parameters changed. Then, they attempt to assemble a mental model of what happened.
> 
> Most AI dev tools today simply take a stack trace, dump it into an LLM prompt, and ask: *'Why did this break?'* The LLM guesses based on general knowledge, frequently hallucinating root causes or recommending trivial code fixes that have nothing to do with what changed in the infrastructure.
> 
> TraceBack 2.0 takes the opposite approach: **investigate what happened, correlate the physical evidence, and validate every single assertion deterministically** before presenting the conclusion to the developer."

---

## Part 2 — Technical Architecture & Investigation Pipeline (1:00)

### Action
1. Point to the UI navigation: Dashboard (`/`), New Investigation (`/new`), History (`/history`).
2. Briefly summarize the 5-step audit trail displayed on every dossier.

### Presenter Talking Points
> "TraceBack executes a multi-stage investigation pipeline:
> 
> 1. **Deterministic Ingestion:** Extracts Git commits, diffs, working tree snapshots, stack traces, and structured log events.
> 2. **Evidence Registry & Hashing:** Every artifact becomes an immutable `EvidenceItem` with a cryptographic SHA-256 integrity hash.
> 3. **Correlation Engine:** Computes a transparent heuristic score $S = T + O + A - C$ representing Temporal proximity, Overlap with crash frames, Agreement with log errors, minus Contradiction penalties.
> 4. **External Research & AI Gateway:** Leverages **Tavily** for external framework error documentation and **Bifrost** as our model gateway to synthesize root-cause hypotheses.
> 5. **Deterministic Claim Validation:** Before the dossier is shown to the user, the `ClaimValidator` independently verifies every assertion against the active registry. If a claim references non-existent evidence or foreign tenant data, it is marked `UNSUPPORTED`.
> 
> Let's see this in action across five concrete production scenarios."

---

## Part 3 — Scenario A: Hikari Connection Pool Failure (2:00)

### Action
1. On Dashboard, click **Scenario A** or navigate directly to:  
   `http://localhost:5173/investigations/9cd50b74-ee3f-451b-b1ea-837b4f6ce195`
2. Point out:
   - Status Pill: `PROBABLE` (Orange badge).
   - Service: `order-service`, Runtime: `RUNTIME_FAILURE`.
   - Primary Suspected Root Cause: Commit `ee51c22` (*"perf(db): optimize connection pool parameters for low memory"*).
   - Heuristic Confidence: `71%` (Score: 60.0 / 85.0).
3. Click the **Confidence Breakdown** section to reveal:
   - `Temporal: +30 pts` (0m prior to incident).
   - `Overlap: +30 pts` (Exact config match on `connection-timeout: 30000 => 2000`).
   - `Agreement: +0 pts`, `Penalty: -0 pts`.
4. Switch to the **Evidence** tab:
   - Filter by **Config Changes**: Show `EVD-CFG-ee51c22` with diff `connection-timeout: 2000`.
   - Filter by **Stack Traces**: Show `java.sql.SQLTimeoutException: Connection timeout after 2000ms`.
5. Switch to the **Agent Steps** tab:
   - Walk through the 5 steps: `Deterministic Ingestion` → `External Technical Research` → `Bifrost AI Gateway Reasoning` → `Evidence Grounding & Claim Validation` → `Final Verdict`.

### Presenter Talking Points
> "Notice the verdict here: it says **PROBABLE**, not **CONFIRMED**.
> 
> Why? Because TraceBack is epistemologically honest. Commit `ee51c22` reduced Hikari's `connection-timeout` from 30 seconds down to 2 seconds, which exactly matches the 2000ms timeout observed in the runtime stack trace.
> 
> However, TraceBack detects that there is no telemetry independently proving that this configuration was deployed to the active Kubernetes pod at runtime. Therefore, our system marks it as `PROBABLE` and generates explicit verification steps: *'Confirm runtime deployment timestamp and verify if pool metrics were saturated.'*
> 
> This prevents engineers from making hasty, unverified rollbacks."

---

## Part 4 — Scenario B: Cross-Language Node.js Runtime Failure (1:00)

### Action
1. Navigate via Dashboard or URL to:  
   `http://localhost:5173/investigations/3bc02925-2215-4d9f-a1bf-19b56e139a98`
2. Point out:
   - Service: `user-service` (Node.js Express application).
   - Error: `TypeError: Cannot read properties of undefined (reading 'role')`.
   - Identified Root Cause: Commit `9fb2786` (*"feat(users): add user profile endpoint with nested role access"*).
   - Modified file: `users.js` matching stack frame `router.get:18`.
   - Heuristic Confidence: `71%`.

### Presenter Talking Points
> "TraceBack is not locked to Java. In Scenario B, we analyze a Node.js Express service failure.
> 
> Our composite stack trace parser delegates to a language-specific adapter that parses V8 JavaScript stack traces, maps line 18 in `users.js` to the Git diff of commit `9fb2786`, and correlates the nested property dereference.
> 
> TraceBack provides uniform evidence cards and timeline correlation across languages without changing the agent workflow."

---

## Part 5 — Scenario C & Historical TacitIQ: Working-Tree Forensics (1:30)

### Action
1. Navigate to Scenario C:  
   `http://localhost:5173/investigations/74880eda-145c-4ebb-bdd2-630233cc87a5`
2. Point out:
   - Mode: `WORKING_TREE_FORENSICS`.
   - Verdict: `CONFIRMED` (Green badge).
   - Primary Root Cause: *"Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established."*
3. Now open the comprehensive historical demonstration:  
   `http://localhost:5173/investigations/5c48eb24-a00e-435a-aff8-88879ea113fb`
4. Show the scale:
   - **103 Evidence items**, **17 Claims**, **35 Timeline events**.
   - Evidence Explorer: Filter by **Working Tree Changes** tab.
   - Show the 79 missing files (`backend/src/main/java/com/tacitiq/...`) and 1 modified `README.md`.
5. Click on the **Claims** tab:
   - Show that 9 claims are `SUPPORTED` and 8 are `PARTIALLY_SUPPORTED`.
   - Click a supporting evidence ID (e.g., `EVD-WT-MISSING-...`): demonstrate that the UI automatically jumps to the Evidence Explorer and highlights the exact evidence card with a green `SELECTED FROM CLAIM` badge.

### Presenter Talking Points
> "What happens when an incident isn't caused by a bad commit, but by an external disk anomaly, a runaway build script, or an accidental filesystem purge?
> 
> This is TraceBack's **Working-Tree Forensics Mode**.
> 
> In this real-world TacitIQ repository, 79 Java source files vanished from the disk while remaining tracked in Git HEAD `d711d65`. TraceBack captured every missing file as an individual evidence record.
> 
> Crucially, note the phrasing of the verdict: it is `CONFIRMED` that the files are physically missing from disk, but it explicitly states that *'The process responsible for their disappearance is not established.'* We do not fabricate an imaginary rogue process when OS-level audit logs are absent."

---

## Part 6 — Scenario D: Honest Abstention on Insufficient Evidence (1:00)

### Action
1. Navigate to:  
   `http://localhost:5173/investigations/59bbecc4-41d4-49ef-8b1b-07c34d5153d3`
2. Point out:
   - Verdict: `INSUFFICIENT_EVIDENCE` (Gray badge).
   - Primary Root Cause: *"INSUFFICIENT_EVIDENCE: Available telemetry does not contain code or configuration changes corresponding to the observed failure (SocketTimeoutException: Connect to payment-gateway... failed)."*
   - Hypotheses List: Shows *"No hypotheses exceeded confidence threshold."*
   - Confidence breakdown: Null (no misleading numbers displayed).

### Presenter Talking Points
> "Every AI vendor claims their agent is smart. We believe an agent is only as smart as its willingness to say: **'I don't know.'**
> 
> In Scenario D, the application suffered an upstream network timeout reaching an external payment gateway. We fed the agent repository history and the runtime stack trace.
> 
> A naive LLM would guess that someone changed the HTTP client timeout. But TraceBack scanned the commit history, found zero changes to network configs, and refused to guess. It returns `INSUFFICIENT_EVIDENCE`.
> 
> In mission-critical enterprise engineering, a false positive wastes hours of incident response time. TraceBack guarantees honest abstention."

---

## Part 7 — Scenario E: Anti-Recency Bias & Deep Configuration Diffs (1:00)

### Action
1. Navigate to:  
   `http://localhost:5173/investigations/c53c484c-8c43-4bf9-a631-62e60bd28ff5`
2. Point out:
   - Verdict: `CONFIRMED`.
   - Identified Root Cause: Commit `c881289` (*"refactor(config): severely restrict connection pool size and timeout"*).
   - Heuristic Confidence: `76%`.
   - Explain the Git history: Commit `c881289` was NOT the most recent commit. A later commit made cosmetic documentation updates.

### Presenter Talking Points
> "A frequent trap in incident triage is **Recency Bias**—blaming the latest commit pushed to `main`.
> 
> In Scenario E, the most recent commit was a harmless documentation tweak. Two commits earlier, an engineer restricted `spring.datasource.hikari.maximum-pool-size` from 20 to 2, causing PostgreSQL connection starvation.
> 
> TraceBack's Correlation Engine evaluated both commits. The recent commit received 0 overlap points, while the older configuration commit earned 25 overlap points and 10 agreement points, ranking it as the primary culprit."

---

## Part 8 — Closing & Hackathon Judge Alignment (1:00)

### Action
1. Navigate to `/history` to display the complete inventory of all 15 recorded investigations.
2. Open `/settings` to show environment indicators (Bifrost Gateway URL, Tavily search configuration, allowed repository boundaries).

### Presenter Talking Points
> "To summarize what makes TraceBack 2.0 unique:
> 
> - **Bifrost AI Gateway Integration:** We decouple agent orchestration from model providers, allowing hot-swapping of frontier models through Bifrost while enforcing structured JSON schemas.
> - **Tavily Technical Research:** External error diagnostics and framework documentation are retrieved dynamically to inform reasoning.
> - **Deterministic Evidence Grounding:** AI never makes an ungrounded claim. Every assertion is backed by SHA-256 verified evidence and checked by an independent `ClaimValidator`.
> - **Multi-Tenant Data Isolation:** Under our PostgreSQL schema V4, evidence and claims are strictly partitioned by investigation UUIDs, eliminating cross-investigation contamination.
> 
> TraceBack is ready for deployment in modern CI/CD and incident management platforms. Thank you, and we welcome your questions!"

---

## 2. Judge Q&A Defense Sheet

### Q1: "Did you use Bifrost and Tavily live during this demonstration?"
> **Honest Answer:** "During local development and automated CI testing, our suite runs with deterministic mocks and fallbacks to ensure zero external network flakiness. Our codebase contains complete HTTP clients for Bifrost's OpenAI-compatible completions API (`DefaultBifrostClient`) and Tavily's search API (`DefaultTavilyResearchService`). In production, providing `BIFROST_API_KEY` and `TAVILY_API_KEY` environment variables activates live upstream calls seamlessly."

### Q2: "Is your 71% confidence score a probability?"
> **Honest Answer:** "No. We explicitly distinguish heuristic ranking scores from statistical probabilities. The 71% is a normalized score derived from our deterministic scoring formula: $S = T + O + A - C$ (Temporal proximity + Overlap + Agreement - Contradiction penalty), divided by the theoretical maximum of 85 points. It orders competing hypotheses transparently so developers can inspect exactly why a commit was ranked."

### Q3: "Can TraceBack investigate any Git repository on my machine?"
> **Honest Answer:** "Yes, provided it is within an authorized repository path. For security, TraceBack enforces a strict filesystem allowlist (`traceback.security.allowed-repo-roots`), canonicalizes real paths, and prevents symlink breakout or directory traversal."

### Q4: "What happens if an LLM hallucinates a root cause that wasn't in the code?"
> **Honest Answer:** "Our `ClaimValidator` runs as an independent deterministic gate *after* agent reasoning. If the agent generates a claim citing an invalid or foreign evidence ID, or if the SHA-256 hash fails, the claim is marked `UNSUPPORTED`. Furthermore, our `InvestigationAgent` has hardcoded guardrails preventing LLM verdict overrides when telemetry indicates `INSUFFICIENT_EVIDENCE`."
