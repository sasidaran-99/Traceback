# Traceback 2.0 — Frontend Investigation Dashboard

The developer-facing interactive investigation dashboard for **Traceback 2.0**, an evidence-driven software failure investigation platform.

> **Product Promise:**  
> *"Don't just tell developers that something broke. Investigate what happened, explain why, and show the evidence."*

---

## Architecture & Technology Stack

- **Framework**: React 18 + TypeScript (strict typing enabled)
- **Build Tool**: Vite 5
- **Styling**: Tailwind CSS (dark-first theme with obsidian backgrounds and restrained accents)
- **Icons**: Lucide React
- **Routing**: React Router DOM v6
- **Testing**: Vitest + React Testing Library + jsdom
- **Backend API Layer**: Native Fetch client with timeout controls, typed REST endpoints, and error normalization (`ApiError`).

---

## Directory Structure

```
frontend/
├── src/
│   ├── api/
│   │   ├── client.ts             # Typed fetch wrapper with timeout and error handling
│   │   ├── investigationApi.ts   # Client service for all 12 backend REST endpoints
│   │   └── types.ts              # Canonical TypeScript interfaces matching Spring Boot DTOs
│   ├── components/
│   │   ├── dashboard/            # StatsOverview, RecentInvestigations
│   │   ├── evidence/             # EvidenceExplorer (registry table, SHA-256 hashes, filters)
│   │   ├── investigation/        # SummaryCard, HypothesesList, ConfidenceBreakdownView,
│   │   │                         # ClaimValidationTable, ExternalResearchList, AgentAuditTrail
│   │   ├── layout/               # Header (with live backend ping pill), Sidebar
│   │   ├── shared/               # Badge, StatusPill, CodeBlock, EmptyState, LoadingSpinner
│   │   └── timeline/             # ChronologicalTimeline (pre/post-failure event sequence)
│   ├── hooks/
│   │   └── useHealth.ts          # Periodic /api/health poller and manual refresh
│   ├── pages/
│   │   ├── DashboardPage.tsx     # Hero banner, platform principles, stats, recent runs
│   │   ├── NewInvestigationPage.tsx # Workflow A (new analysis) & Workflow B (existing ID)
│   │   ├── InvestigationDetailPage.tsx # 7-tab central dossier view
│   │   ├── HistoryPage.tsx       # Searchable, filterable table of all investigations
│   │   └── SettingsPage.tsx      # System health, gateway configs, and security posture
│   ├── test/                     # Unit and component tests (Vitest)
│   ├── utils/
│   │   └── formatters.ts         # Confidence percentages, ISO dates, duration, hashes
│   ├── App.tsx                   # Top-level routing layout
│   ├── index.css                 # Tailwind directives, custom scrollbars, styling
│   └── main.tsx                  # React 18 root entry point
├── package.json
├── tailwind.config.js
├── tsconfig.json
└── vite.config.ts
```

---

## Running Locally

### Prerequisites
- Node.js 18+ (tested with Node 20 / 22)
- Spring Boot backend running on `http://localhost:8080` (or configured via environment)

### 1. Install Dependencies
```bash
cd frontend
npm install
```

### 2. Configure Environment (Optional)
By default, Vite proxies `/api` to `http://localhost:8080`. To override:
```bash
cp .env.example .env
# Edit VITE_API_BASE_URL if your backend runs on a different port or host
```

### 3. Start Development Server
```bash
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser.

### 4. Build for Production
```bash
npm run build
```
Generates production assets in `frontend/dist/`.

### 5. Run Unit and Component Tests
```bash
npm test
```

---

## Security Model

1. **Zero Client Secret Leakage**: Neither Bifrost nor Tavily API keys are ever stored in or transmitted to the browser client. All external intelligence operations remain strictly behind Spring Boot backend controllers.
2. **Cryptographic Integrity Verification**: All local evidence items feature SHA-256 payload checksums displayed directly in the Evidence Explorer.
3. **Isolated Sandbox Research**: Web search results from Tavily provide external diagnostic context but are strictly quarantined from local evidence IDs and cannot artificially raise confidence scores.
