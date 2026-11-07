# Traceback

> **Evidence-Driven Application Failure Investigation Platform**  
> Reconstructing events across development and runtime systems to identify, explain, and help verify the most likely root cause of software failures.

---

## 1. Project Objective

When a software failure occurs in production or staging, developers typically spend hours manually piecing together what happened across fragmented systems: examining log viewers, checking CI build results, browsing git commit histories, and inspecting deployment logs.

**Traceback** eliminates this manual detective work by correlating events across development and runtime lifecycles. It reconstructs the causal timeline to provide an evidence-backed hypothesis of the root cause, accompanied by concrete verification steps for the on-call engineer.

### The Five Critical Questions Traceback Answers

1. **What happened?** — What was the failure symptom, error rate, and impact?
2. **What changed before the failure?** — Which code commits, configuration updates, or deployments occurred within the incident window?
3. **Which events are related, and why?** — How are commits linked to builds, deployments, and runtime stack traces?
4. **What evidence supports the most likely root cause?** — What deterministic, inferred, and temporal evidence supports the hypothesis?
5. **What should the developer investigate next?** — What explicit, actionable steps can a developer take to verify or disprove the finding?

---

## 2. What Traceback Is (and Is Not)

| Traceback Is | Traceback Is Not |
| :--- | :--- |
| **An evidence-driven causal investigation engine** | A passive log viewer or ELK/Datadog replica |
| **A cross-system event reconstruction platform** | A simple GitHub commit viewer or dashboard |
| **A deterministic, evidence-ranked hypothesis generator** | A naive "blame the most recent commit" ranking tool |
| **A structured decision-support system with explicit uncertainty** | An ungrounded, hallucination-prone AI chatbot |

---

## 3. Technology Stack

- **Backend Platform:** Java 21, Spring Boot 3.x, Maven
- **Database & Event Store:** PostgreSQL (relational event streams with JSONB structured metadata and indexing)
- **Frontend Dashboard:** React 18, TypeScript, Vite, Tailwind CSS
- **Containerization & Local Dev:** Docker, Docker Compose
- **Source & CI Integration:** GitHub REST API v3, GitHub Webhooks, CI Ingestion REST API
- **Deferred Technologies:** Apache Kafka (streaming broker), Redis, Neo4j, pgvector, and LLM narrative generators are intentionally deferred to Phase 5 when justified. Traceback begins as a **clean, modular Spring Boot monolith**.

---

## 4. Repository Structure & Documentation Index

```text
TraceBack/
├── docs/
│   ├── architecture.md          # Architectural blueprints, event modeling, correlation design
│   ├── incident-scenarios.md    # Reproducible scenarios (A: Code, B: Config, C: Misleading commit)
│   ├── evaluation-plan.md       # Baseline comparison, measurable metrics, evaluation harness
│   └── roadmap.md               # Phased delivery plan from Phase 0 to Phase 5
├── traceback-core/              # Domain models, repositories, Git collector, Log analyzer, Stack trace parser, Evidence registry, Correlation engine
├── traceback-ingestion/         # Event ingestion DTOs, validation, service, and POST /api/events endpoint
├── traceback-api/               # Spring Boot application, investigation controller, REST APIs, Flyway migrations
├── frontend/                    # Interactive Investigation Dashboard (React 18, TypeScript, Vite, Tailwind CSS)
├── docker-compose.yml           # PostgreSQL 16 container definition with healthchecks
├── pom.xml                      # Maven multi-module root POM
├── .env.example                 # Local environment template with placeholders
├── .gitignore                   # Multi-stack ignore rules (Java, Maven, Vite, IDEs)
└── README.md                    # Project overview and index
```

### Comprehensive Documentation

- [**System Architecture (`docs/architecture.md`)**](docs/architecture.md): Problem definition, component responsibilities, end-to-end investigation workflow, event normalization, evidence scoring, and technology trade-offs.
- [**Incident Scenarios (`docs/incident-scenarios.md`)**](docs/incident-scenarios.md): Specification of three reproducible failure scenarios on a hypothetical Spring Boot payment service (Code regression, Configuration failure, Misleading recent commit).
- [**Evaluation Methodology (`docs/evaluation-plan.md`)**](docs/evaluation-plan.md): Benchmark metrics (Top-3 root-cause accuracy, false-positive relationship rate, latency, developer triage time) comparing Traceback against the naive "Most Recent Commit" baseline.
- [**Implementation Roadmap (`docs/roadmap.md`)**](docs/roadmap.md): Detailed objectives, deliverables, dependencies, and acceptance criteria for Phases 0 through 5.

---

## 5. Current Project Status

- **Phase 0 (Project Foundation and Investigation Design):** Complete.
- **Phase 1 (Spring Boot Backend, PostgreSQL and Event Ingestion APIs):** Complete.
- **Phase 2 (Deterministic Investigation Engine, Git/Log Analysis & Correlation):** Complete.
  - Local repository Git inspection and line-level diff analyzer via JGit.
  - Robust multiline Java and Spring Boot stack trace parser with nested cause resolution.
  - Multi-format log analyzer supporting Spring multiline logs and JSON structured logs.
  - Unified chronological timeline builder.
  - 100% deterministic correlation engine implementing Rules A–E without an LLM.
- **Phase 3 (Evidence Validation, Transparent Confidence Engine & Evaluation Harness):** Complete.
  - **Concrete Claim Validation:** Strict `ClaimValidator` implementation verifying claims against registered evidence IDs, rejecting missing evidence, foreign investigation items, non-supporting diffs, and unaddressed contradictions.
  - **Four-Tier Epistemological Framework:** Rigorous distinction between *Evidence Integrity* (SHA-256), *Claim Validation* (support checks), *Heuristic Confidence* ($S = T + O + A - C$), and *Statistically Measured Accuracy* (evaluation harness).
  - **Transparent Confidence Breakdown:** Normalized scoring $S = T + O + A - C$ ($T \le 30, O \le 30, A \le 25, C \le 30$), enforcing that temporal proximity alone never establishes causation.
  - **Honest Abstention:** Explicit `INSUFFICIENT_EVIDENCE` generation and rejection when available telemetry cannot substantiate a causal link.
  - **Deterministic Evaluation Harness:** Empirical benchmark across 5 scenarios measuring Top-1 Accuracy (100%), Top-3 Accuracy (100%), False-Positive Relationship Rate (0.0%), Correct Abstention Rate (100%), and average latency (2.6 ms).
  - **New Claims REST API:** `GET /api/investigations/{id}/claims`.
- **Phase 4 (Autonomous AI Investigation Agent & External Technical Research):** Complete.
  - **Bifrost AI Gateway Integration:** OpenAI-compatible client supporting virtual keys (`sk-bf-...`), model routing, timeout control, and bounded retries for HTTP 429 and 5xx errors.
  - **Tavily Technical Research API:** Automated research service querying library documentation and bug patterns with query caching and search budgeting.
  - **Autonomous Investigation Agent (`InvestigationAgent`):** Orchestrates deterministic context ingestion, technical research, LLM reasoning, claim grounding, and step auditing.
  - **Evidence Grounding Invariants:** AI-generated claims citing unverified evidence IDs are strictly rejected as `UNSUPPORTED` via `ClaimValidator`; external research findings are cleanly isolated from repository `EvidenceItem` records; confidence scores remain grounded in the deterministic formula.
  - **Deterministic Fallback:** Robust fallback reasoning when external AI services are unconfigured or offline.
  - **New Agent REST APIs:**
    - `POST /api/investigations/agent`: Trigger end-to-end autonomous investigation.
    - `GET /api/investigations/{id}/agent-report`: Retrieve structured AI agent investigation report.
    - `GET /api/investigations/{id}/research`: Retrieve external technical research findings and citations.
    - `GET /api/investigations/{id}/agent-steps`: Retrieve auditable sequential investigation trail.
- **Phase 5 (Interactive Investigation Dashboard — React + TypeScript + Vite):** Complete.
  - **Full-Stack Presentation Layer:** Polished, dark-first investigation dashboard built with React 18, TypeScript, Vite, Tailwind CSS, and Lucide icons.
  - **Single Source of Truth:** Connects directly to the Spring Boot REST API (`http://localhost:8080`) displaying real backend investigation records, verified evidence, and agent reasoning.
  - **Key Views & Capabilities:**
    - **Dashboard (`/`)**: Product hero banner, live system statistics (`StatsOverview`), and recent investigations table.
    - **New Investigation (`/new`)**: Dual workflow support (Workflow A: new analysis from repository path + logs/stack traces; Workflow B: autonomous agent dispatch on existing ID) with interactive scenario presets (HikariCP starvation, Division by zero).
    - **Investigation Dossier (`/investigations/:id`)**: Comprehensive 7-tab deep-dive view covering Overview/AI Report, Ranked Hypotheses, Claim Grounding Table, Evidence Explorer (SHA-256 integrity hashes), Reconstructed Chronological Timeline, External Technical Research (Tavily), and Sequential Agent Audit Trail.
    - **History (`/history`)**: Searchable, status-filterable table of all investigations.
    - **Settings (`/settings`)**: Live backend health monitor, API gateway configuration status, scoring policy reference, and zero-client-secret security posture.
  - **Security Architecture:** Bifrost and Tavily API keys remain strictly on the Spring Boot backend; zero credentials exposed in client JavaScript.
  - **Quality & Testing:** 17 frontend Vitest tests + Spring Boot integration tests (100% green).
- **Phase 6 (End-to-End Integration, Validation & Hackathon Demonstration):** Complete.
  - **10 Core Demonstration Capabilities:** Automated end-to-end integration test (`EndToEndInvestigationDemonstrationTest`) verifying telemetry ingestion, Git history analysis via JGit, stack trace/log parsing, deterministic root-cause correlation ($S = T + O + A - C$), evidence validation (SHA-256 integrity hashes), Bifrost AI investigation, Tavily external technical research, strict claim grounding, frontend REST API endpoints, and honest insufficient-evidence abstention.
  - **Repository Path Security Hardening:** Strict configurable allowlist (`TRACEBACK_ALLOWED_REPO_ROOTS`), real-path canonicalization, path probing defense, and HTTP 403 Forbidden enforcement via `SecurityException`.
  - **Production CORS Hardening:** Fail-safe startup check in `CorsConfig` preventing wildcard or localhost origins when running under the `prod` profile.
  - **Live PostgreSQL 16+ Integration:** Verified against live PostgreSQL (`PostgreSQL 18.3/16` on `localhost:5432`) via `PostgreSql16IntegrationTest` (8/8 tests passing: Flyway schema validation, JSONB column metadata queries, unique constraints, ON DELETE CASCADE foreign key constraints, transactional rollback, and multi-transaction persistence).
  - **Quality & Build Verification:** 36 backend tests in multi-module reactor (100% green), 17 frontend Vitest tests (100% green), and production Vite build (100% clean).

---

## 6. Getting Started

### Terminal 1: Start the Spring Boot Backend

```bash
# Ensure Java 21 is active
mvn spring-boot:run -pl traceback-api
```
Backend will start on `http://localhost:8080` (healthcheck: `http://localhost:8080/api/health`).

### Terminal 2: Start the React Frontend Dashboard

```bash
cd frontend
npm install
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser. Launch an investigation by selecting a preset on the `/new` page or providing your local repository path.

