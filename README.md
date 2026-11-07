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
├── traceback-core/              # Domain models (Event, Relationship, Incident, Hypothesis), Enums, Repositories
├── traceback-ingestion/         # Event ingestion DTOs, validation, service, and POST /api/events endpoint
├── traceback-api/               # Spring Boot entry point, health endpoint, exception handler, Flyway migrations
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
- **Next Phase:** **Phase 2 — GitHub Integration and Application Error Ingestion**.
