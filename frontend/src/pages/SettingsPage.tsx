import React from 'react';
import { Link } from 'react-router-dom';
import { useHealth } from '../hooks/useHealth';
import {
  Server,
  ShieldCheck,
  Key,
  Cpu,
  Search,
  Database,
  Code,
  CheckCircle2,
  RefreshCw,
  Settings,
  ChevronRight,
  FolderGit2,
  Lock,
} from 'lucide-react';
import { Badge } from '../components/shared/Badge';

export const SettingsPage: React.FC = () => {
  const { isOnline, health, isChecking, error, checkHealth } = useHealth();
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

  return (
    <div className="space-y-6 max-w-5xl mx-auto font-sans">
      {/* Breadcrumb Navigation */}
      <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-xs font-mono text-[#6B7585]">
        <Link to="/" className="hover:text-[#7FA3C4] transition-colors flex items-center gap-1">
          <span>Platform</span>
        </Link>
        <ChevronRight className="w-3.5 h-3.5 text-[#434D5C]" />
        <span className="text-[#E4E8EE] font-medium">Settings</span>
      </nav>

      {/* Header */}
      <div className="pb-4 border-b border-[#2A323C]">
        <h1 className="text-xl sm:text-2xl font-mono font-bold text-[#E4E8EE] flex items-center gap-2.5">
          <Settings className="w-6 h-6 text-[#7FA3C4]" />
          System Settings &amp; Configuration
        </h1>
        <p className="text-xs sm:text-sm text-[#A7B0BC] mt-1.5 leading-relaxed font-sans">
          Review core engine runtime status, external AI research gateways, deterministic scoring parameters, and authorized repository paths.
        </p>
      </div>

      {/* 1. Spring Boot Backend Runtime Subsystem */}
      <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-6 shadow-xs space-y-5">
        <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-[#1F262E]">
          <div className="flex items-center gap-3">
            <div className="p-2.5 bg-[#1A2129] rounded-lg border border-[#2A323C] text-[#7FA3C4]">
              <Server className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-[#E4E8EE]">Spring Boot Backend Runtime</h2>
              <p className="text-xs text-[#6B7585] font-mono mt-0.5">
                Host: <code className="text-[#E4E8EE] font-mono">{apiBaseUrl}</code>
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={() => checkHealth()}
              disabled={isChecking}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-[#A7B0BC] hover:text-[#E4E8EE] bg-[#1A2129] hover:bg-[#222B35] border border-[#2A323C] rounded-lg transition-colors font-mono"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isChecking ? 'animate-spin' : ''}`} />
              Check Health
            </button>
            <div className="flex items-center gap-2 px-2.5 py-1 rounded-md bg-[#0C1014] border border-[#1F262E] font-mono text-xs">
              <span className={`w-2 h-2 rounded-full ${isOnline ? 'bg-[#4FA88A]' : 'bg-[#C86A6A]'}`} />
              <span className="font-semibold text-[#E4E8EE]">
                {isOnline ? 'ONLINE' : 'OFFLINE'}
              </span>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
          <div className="p-3.5 bg-[#0C1014] rounded-lg border border-[#1F262E]">
            <span className="text-[#6B7585] block mb-1">Health Status</span>
            <span className="text-[#E4E8EE] font-semibold">
              {health?.status || (error ? 'DOWN / UNREACHABLE' : 'CONNECTING...')}
            </span>
          </div>
          <div className="p-3.5 bg-[#0C1014] rounded-lg border border-[#1F262E]">
            <span className="text-[#6B7585] block mb-1">Traceback Engine Version</span>
            <span className="text-[#E4E8EE] font-semibold">2.0.0-SNAPSHOT (Java 21)</span>
          </div>
          <div className="p-3.5 bg-[#0C1014] rounded-lg border border-[#1F262E]">
            <span className="text-[#6B7585] block mb-1">Last Health Ping</span>
            <span className="text-[#A7B0BC]">
              {health?.timestamp ? new Date(health.timestamp).toLocaleTimeString() : 'N/A'}
            </span>
          </div>
        </div>
      </div>

      {/* 2. Core Subsystems & Integration Gateways Grid */}
      <div className="space-y-4">
        <h2 className="text-sm font-mono font-bold uppercase tracking-wider text-[#6B7585]">
          Subsystems &amp; Integration Architecture
        </h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          
          {/* Bifrost AI Gateway - Neutral & Steel Blue */}
          <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-5 space-y-3.5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Key className="w-5 h-5 text-[#7FA3C4]" />
                <h3 className="text-sm font-semibold text-[#E4E8EE]">Bifrost AI Gateway</h3>
              </div>
              <Badge variant="blue">gemini-3.8-flash</Badge>
            </div>
            <p className="text-xs text-[#A7B0BC] leading-relaxed font-sans">
              Local enterprise AI routing gateway (<code className="text-[#E4E8EE] font-mono text-[11px]">http://localhost:8181/v1</code>) serving the Autonomous Agent. Authenticates via Virtual Key (<code className="text-[#E4E8EE] font-mono text-[11px]">x-bf-vk</code>) with bounded retries and 180s timeout.
            </p>
            <div className="text-[11px] font-mono text-[#6B7585] bg-[#0C1014] p-2.5 rounded-lg border border-[#1F262E] space-y-1">
              <div className="flex items-center justify-between">
                <span>Model / Provider:</span>
                <span className="text-[#7FA3C4] font-semibold">gemini-3.8-flash (Google Gemini)</span>
              </div>
              <div className="flex items-center justify-between">
                <span>Virtual Key (Masked):</span>
                <span className="text-[#4FA88A] flex items-center gap-1 font-semibold">
                  <Lock className="w-3 h-3" />
                  sk-bf-***fa65
                </span>
              </div>
            </div>
          </div>

          {/* Deterministic Scoring Engine */}
          <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-5 space-y-3.5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Cpu className="w-5 h-5 text-[#7FA3C4]" />
                <h3 className="text-sm font-semibold text-[#E4E8EE]">Deterministic Scoring Engine</h3>
              </div>
              <Badge variant="neutral">Rule-Based</Badge>
            </div>
            <p className="text-xs text-[#A7B0BC] leading-relaxed font-sans">
              Executes JGit commit diffing, Java stack trace parsing, and structured log correlation. Operates deterministically without requiring external LLM availability.
            </p>
            <div className="text-[11px] font-mono text-[#6B7585] bg-[#0C1014] p-2.5 rounded-lg border border-[#1F262E] space-y-1">
              <div className="flex items-center justify-between">
                <span>Scoring Formula:</span>
                <span className="text-[#E4E8EE] font-semibold">S = T + O + A - C</span>
              </div>
              <div className="flex items-center justify-between">
                <span>Normalization Scale:</span>
                <span className="text-[#A7B0BC]">Normalized = max(0, S) / 85.0</span>
              </div>
            </div>
          </div>

          {/* Technical Research Service */}
          <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-5 space-y-3.5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Search className="w-5 h-5 text-[#7FA3C4]" />
                <h3 className="text-sm font-semibold text-[#E4E8EE]">Tavily Technical Research</h3>
              </div>
              <Badge variant="blue">Isolated Sandbox</Badge>
            </div>
            <p className="text-xs text-[#A7B0BC] leading-relaxed font-sans">
              External web search agent providing authoritative documentation, bug tracker analysis, and vendor advisories without contaminating local evidence checksums.
            </p>
            <div className="text-[11px] font-mono text-[#6B7585] bg-[#0C1014] p-2.5 rounded-lg border border-[#1F262E] flex items-center justify-between">
              <span>Research Sandbox:</span>
              <span className="text-[#7FA3C4] font-semibold">Non-Evidence Isolated</span>
            </div>
          </div>

          {/* PostgreSQL 15 & Persistence */}
          <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-5 space-y-3.5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Database className="w-5 h-5 text-[#4FA88A]" />
                <h3 className="text-sm font-semibold text-[#E4E8EE]">PostgreSQL 15 &amp; Flyway</h3>
              </div>
              <Badge variant="green">Flyway V1..V4</Badge>
            </div>
            <p className="text-xs text-[#A7B0BC] leading-relaxed font-sans">
              Relational event store maintaining events, relationships, incidents, and SHA-256 evidence registry records with JSONB metadata and indexing.
            </p>
            <div className="text-[11px] font-mono text-[#6B7585] bg-[#0C1014] p-2.5 rounded-lg border border-[#1F262E] flex items-center justify-between">
              <span>Database Instance:</span>
              <span className="text-[#A7B0BC] font-semibold">localhost:5432/traceback_db</span>
            </div>
          </div>

        </div>
      </div>

      {/* 3. Security & Safety Architectural Boundaries */}
      <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-6 space-y-4">
        <div className="flex items-center gap-2.5 text-[#4FA88A]">
          <ShieldCheck className="w-5 h-5" />
          <h2 className="text-base font-semibold text-[#E4E8EE]">Security Architecture &amp; Credentials Policy</h2>
        </div>
        <p className="text-xs text-[#A7B0BC] leading-relaxed font-sans">
          TraceBack enforces a zero-trust model between the web browser and external AI/search providers:
        </p>
        <ul className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs text-[#A7B0BC] font-sans">
          <li className="flex items-start gap-2.5 bg-[#0C1014] p-3.5 rounded-lg border border-[#1F262E]">
            <CheckCircle2 className="w-4 h-4 text-[#4FA88A] mt-0.5 shrink-0" />
            <span className="leading-relaxed"><strong className="text-[#E4E8EE]">Zero Secret Leakage:</strong> Neither Bifrost virtual keys nor provider keys are ever transferred to or stored in client-side browser memory.</span>
          </li>
          <li className="flex items-start gap-2.5 bg-[#0C1014] p-3.5 rounded-lg border border-[#1F262E]">
            <CheckCircle2 className="w-4 h-4 text-[#4FA88A] mt-0.5 shrink-0" />
            <span className="leading-relaxed"><strong className="text-[#E4E8EE]">Evidence Registry Integrity:</strong> All local evidence artifacts carry SHA-256 checksums verified by the backend ClaimValidator.</span>
          </li>
          <li className="flex items-start gap-2.5 bg-[#0C1014] p-3.5 rounded-lg border border-[#1F262E]">
            <CheckCircle2 className="w-4 h-4 text-[#4FA88A] mt-0.5 shrink-0" />
            <span className="leading-relaxed"><strong className="text-[#E4E8EE]">External Finding Isolation:</strong> External web findings never receive evidence IDs and cannot artificially raise hypothesis confidence.</span>
          </li>
          <li className="flex items-start gap-2.5 bg-[#0C1014] p-3.5 rounded-lg border border-[#1F262E]">
            <CheckCircle2 className="w-4 h-4 text-[#4FA88A] mt-0.5 shrink-0" />
            <span className="leading-relaxed"><strong className="text-[#E4E8EE]">Deterministic Fallback:</strong> If Bifrost or Tavily are unavailable, the engine falls back seamlessly to deterministic investigation.</span>
          </li>
        </ul>
      </div>

      {/* 4. Authorized Repository Roots */}
      <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-6 space-y-3">
        <div className="flex items-center gap-2.5 text-[#7FA3C4]">
          <FolderGit2 className="w-5 h-5" />
          <h2 className="text-base font-semibold text-[#E4E8EE]">Authorized Repository Roots</h2>
        </div>
        <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
          The backend Git collector enforces strict path boundaries. Only repositories located under configured authorized root directories may be inspected:
        </p>
        <div className="p-3 bg-[#0C1014] rounded-lg border border-[#1F262E] font-mono text-xs text-[#A7B0BC]">
          <code className="text-[#7FA3C4]">C:\Users\Sasidaran\Desktop\Sasidaran\Projects\TraceBack\scratch\validation_repos</code>
        </div>
      </div>

      {/* 5. REST API Endpoints Reference */}
      <div className="bg-[#141A20] border border-[#2A323C] rounded-xl p-6 space-y-4">
        <div className="flex items-center gap-2.5 text-[#E4E8EE]">
          <Code className="w-5 h-5 text-[#7FA3C4]" />
          <h2 className="text-base font-semibold text-[#E4E8EE]">Backend REST API Reference</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs font-mono">
            <thead>
              <tr className="border-b border-[#2A323C] text-[#6B7585]">
                <th className="py-2.5 px-3">Method</th>
                <th className="py-2.5 px-3">Endpoint</th>
                <th className="py-2.5 px-3 font-sans">Description</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#1F262E] text-[#A7B0BC]">
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#4FA88A] font-bold">POST</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations <span className="text-[#6B7585] font-sans text-[11px]">(alias: /analyze)</span></td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Trigger deterministic investigation</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">List all past investigations</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Fetch investigation detail &amp; hypotheses</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}/timeline</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Chronological timeline of commits and errors</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}/evidence</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Registered evidence items &amp; SHA-256 hashes</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}/claims</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Deterministic claim grounding results</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#4FA88A] font-bold">POST</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/agent <span className="text-[#6B7585] font-sans text-[11px]">(alias: /agent/run)</span></td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Run autonomous AI investigation agent</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/agent</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">List all past AI agent reports</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}/agent-report <span className="text-[#6B7585] font-sans text-[11px]">(alias: /agent/{'{id}'})</span></td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Fetch AI agent report &amp; reasoning</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}/research <span className="text-[#6B7585] font-sans text-[11px]">(alias: /agent/{'{id}'}/research)</span></td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">External Tavily technical research findings</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/investigations/{'{id}'}/agent-steps <span className="text-[#6B7585] font-sans text-[11px]">(alias: /agent/{'{id}'}/audit)</span></td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Sequential autonomous agent audit trail</td>
              </tr>
              <tr className="hover:bg-[#1A2129] transition-colors">
                <td className="py-2.5 px-3 text-[#7FA3C4] font-bold">GET</td>
                <td className="py-2.5 px-3 text-[#E4E8EE]">/api/health</td>
                <td className="py-2.5 px-3 font-sans text-[#A7B0BC]">Engine health and status check</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default SettingsPage;
