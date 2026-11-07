import React from 'react';
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
  RefreshCw
} from 'lucide-react';
import { Badge } from '../components/shared/Badge';

export const SettingsPage: React.FC = () => {
  const { isOnline, health, isChecking, error, checkHealth } = useHealth();
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

  return (
    <div className="space-y-8 max-w-5xl">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-semibold text-dark-50 tracking-tight">System Settings & Engine Configuration</h1>
        <p className="text-sm text-dark-400 mt-1">
          Review backend engine connectivity, API gateways, confidence scoring policies, and security posture.
        </p>
      </div>

      {/* Backend Engine Status Card */}
      <div className="bg-dark-900 border border-dark-800 rounded-xl p-6 shadow-sm">
        <div className="flex items-center justify-between pb-4 border-b border-dark-800 mb-6">
          <div className="flex items-center gap-3">
            <div className="p-2.5 bg-dark-800 rounded-lg border border-dark-700 text-brand-400">
              <Server className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-medium text-dark-100">Spring Boot Backend Runtime</h2>
              <p className="text-xs text-dark-400">Host: <code className="text-dark-200 font-mono">{apiBaseUrl}</code></p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={() => checkHealth()}
              disabled={isChecking}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-dark-300 hover:text-dark-100 bg-dark-800 hover:bg-dark-700 border border-dark-700 rounded-lg transition-colors"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isChecking ? 'animate-spin' : ''}`} />
              Check Health
            </button>
            <div className="flex items-center gap-2">
              <span className={`w-2.5 h-2.5 rounded-full ${isOnline ? 'bg-emerald-400 animate-pulse' : 'bg-red-400'}`} />
              <span className="text-xs font-medium text-dark-200">
                {isOnline ? 'ONLINE' : 'OFFLINE'}
              </span>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
          <div className="p-3.5 bg-dark-950/60 rounded-lg border border-dark-800/80">
            <span className="text-dark-400 block mb-1">Health Status</span>
            <span className="font-mono text-dark-200 font-medium">
              {health?.status || (error ? 'DOWN / UNREACHABLE' : 'CONNECTING...')}
            </span>
          </div>
          <div className="p-3.5 bg-dark-950/60 rounded-lg border border-dark-800/80">
            <span className="text-dark-400 block mb-1">Traceback Engine Version</span>
            <span className="font-mono text-dark-200 font-medium">2.0.0-SNAPSHOT (Java 21)</span>
          </div>
          <div className="p-3.5 bg-dark-950/60 rounded-lg border border-dark-800/80">
            <span className="text-dark-400 block mb-1">Last Health Ping</span>
            <span className="font-mono text-dark-200">
              {health?.timestamp ? new Date(health.timestamp).toLocaleTimeString() : 'N/A'}
            </span>
          </div>
        </div>
      </div>

      {/* Integration Services Grid */}
      <div>
        <h2 className="text-lg font-medium text-dark-100 mb-4">Core Subsystems & Integration Gateways</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          
          {/* Deterministic Engine */}
          <div className="bg-dark-900 border border-dark-800 rounded-xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Cpu className="w-5 h-5 text-indigo-400" />
                <h3 className="text-sm font-semibold text-dark-200">Deterministic Engine</h3>
              </div>
              <Badge variant="purple">Core Rule</Badge>
            </div>
            <p className="text-xs text-dark-400 leading-relaxed">
              Executes JGit commit diffing, Java stack trace parsing, and structured log correlation. Operates deterministically without requiring external LLM availability.
            </p>
            <div className="text-[11px] font-mono text-dark-400 bg-dark-950 px-3 py-2 rounded border border-dark-800">
              Score formula: S = T + O + A - C &isin; [0.0, 1.0]
            </div>
          </div>

          {/* Bifrost AI Gateway */}
          <div className="bg-dark-900 border border-dark-800 rounded-xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Key className="w-5 h-5 text-purple-400" />
                <h3 className="text-sm font-semibold text-dark-200">Bifrost AI Gateway</h3>
              </div>
              <Badge variant="amber">Bifrost / OpenAI</Badge>
            </div>
            <p className="text-xs text-dark-400 leading-relaxed">
              Enterprise AI routing layer connecting the Investigation Agent to models (OpenAI, Claude, Gemini). Governed strictly via backend environment variables.
            </p>
            <div className="text-[11px] text-dark-400 bg-dark-950 px-3 py-2 rounded border border-dark-800 flex items-center justify-between">
              <span>Client Key: Secure (Backend Only)</span>
              <span className="text-emerald-400 font-mono">BIFROST_API_KEY</span>
            </div>
          </div>

          {/* Tavily Research Service */}
          <div className="bg-dark-900 border border-dark-800 rounded-xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Search className="w-5 h-5 text-blue-400" />
                <h3 className="text-sm font-semibold text-dark-200">Tavily Technical Research</h3>
              </div>
              <Badge variant="blue">Isolated Sandbox</Badge>
            </div>
            <p className="text-xs text-dark-400 leading-relaxed">
              External web search agent providing authoritative documentation, bug tracker analysis, and vendor advisories without contaminating local evidence checksums.
            </p>
            <div className="text-[11px] text-dark-400 bg-dark-950 px-3 py-2 rounded border border-dark-800 flex items-center justify-between">
              <span>Research Sandbox: Non-Evidence Isolated</span>
              <span className="text-blue-400 font-mono">TAVILY_API_KEY</span>
            </div>
          </div>

          {/* PostgreSQL 16 Persistence */}
          <div className="bg-dark-900 border border-dark-800 rounded-xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <Database className="w-5 h-5 text-cyan-400" />
                <h3 className="text-sm font-semibold text-dark-200">PostgreSQL 16 & Flyway</h3>
              </div>
              <Badge variant="green">Flyway V1..V2</Badge>
            </div>
            <p className="text-xs text-dark-400 leading-relaxed">
              Relational event store maintaining events, relationships, incidents, and SHA-256 evidence registry records with JSONB metadata and indexing.
            </p>
            <div className="text-[11px] text-dark-400 bg-dark-950 px-3 py-2 rounded border border-dark-800 flex items-center justify-between">
              <span>Database URL</span>
              <span className="text-dark-300 font-mono">localhost:5432/traceback</span>
            </div>
          </div>

        </div>
      </div>

      {/* Security & Architectural Boundaries */}
      <div className="bg-dark-900 border border-dark-800 rounded-xl p-6 space-y-4">
        <div className="flex items-center gap-2.5 text-emerald-400">
          <ShieldCheck className="w-5 h-5" />
          <h2 className="text-base font-medium text-dark-100">Security Architecture & Credentials Policy</h2>
        </div>
        <p className="text-xs text-dark-300 leading-relaxed">
          Traceback enforces a zero-trust model between the web browser and external AI/search providers:
        </p>
        <ul className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs text-dark-400">
          <li className="flex items-start gap-2 bg-dark-950/60 p-3 rounded-lg border border-dark-800">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 mt-0.5 shrink-0" />
            <span><strong>No Secret Leakage:</strong> Neither Bifrost nor Tavily API keys are ever transferred to or stored in client-side browser memory.</span>
          </li>
          <li className="flex items-start gap-2 bg-dark-950/60 p-3 rounded-lg border border-dark-800">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 mt-0.5 shrink-0" />
            <span><strong>Evidence Registry Integrity:</strong> All local evidence artifacts carry SHA-256 checksums verified by the backend ClaimValidator.</span>
          </li>
          <li className="flex items-start gap-2 bg-dark-950/60 p-3 rounded-lg border border-dark-800">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 mt-0.5 shrink-0" />
            <span><strong>External Finding Isolation:</strong> External web findings never receive evidence IDs and cannot artificially raise hypothesis confidence.</span>
          </li>
          <li className="flex items-start gap-2 bg-dark-950/60 p-3 rounded-lg border border-dark-800">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 mt-0.5 shrink-0" />
            <span><strong>Deterministic Fallback:</strong> If Bifrost or Tavily are unavailable, the engine falls back seamlessly to deterministic investigation.</span>
          </li>
        </ul>
      </div>

      {/* REST API Endpoints Reference */}
      <div className="bg-dark-900 border border-dark-800 rounded-xl p-6">
        <div className="flex items-center gap-2.5 mb-4 text-dark-200">
          <Code className="w-5 h-5 text-brand-400" />
          <h2 className="text-base font-medium text-dark-100">Backend REST API Reference</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs font-mono">
            <thead>
              <tr className="border-b border-dark-800 text-dark-400">
                <th className="py-2 px-3">Method</th>
                <th className="py-2 px-3">Endpoint</th>
                <th className="py-2 px-3">Description</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-dark-800/60 text-dark-300">
              <tr>
                <td className="py-2 px-3 text-emerald-400">POST</td>
                <td className="py-2 px-3">/api/investigations/analyze</td>
                <td className="py-2 px-3 font-sans text-dark-400">Trigger deterministic investigation</td>
              </tr>
              <tr>
                <td className="py-2 px-3 text-blue-400">GET</td>
                <td className="py-2 px-3">/api/investigations</td>
                <td className="py-2 px-3 font-sans text-dark-400">List all past investigations</td>
              </tr>
              <tr>
                <td className="py-2 px-3 text-blue-400">GET</td>
                <td className="py-2 px-3">/api/investigations/{'{id}'}</td>
                <td className="py-2 px-3 font-sans text-dark-400">Fetch investigation detail & hypotheses</td>
              </tr>
              <tr>
                <td className="py-2 px-3 text-emerald-400">POST</td>
                <td className="py-2 px-3">/api/investigations/agent/run</td>
                <td className="py-2 px-3 font-sans text-dark-400">Run autonomous AI investigation agent</td>
              </tr>
              <tr>
                <td className="py-2 px-3 text-blue-400">GET</td>
                <td className="py-2 px-3">/api/investigations/agent</td>
                <td className="py-2 px-3 font-sans text-dark-400">List all past AI agent reports</td>
              </tr>
              <tr>
                <td className="py-2 px-3 text-blue-400">GET</td>
                <td className="py-2 px-3">/api/investigations/agent/{'{id}'}</td>
                <td className="py-2 px-3 font-sans text-dark-400">Fetch agent report, claims, audit trail</td>
              </tr>
              <tr>
                <td className="py-2 px-3 text-blue-400">GET</td>
                <td className="py-2 px-3">/api/health</td>
                <td className="py-2 px-3 font-sans text-dark-400">Engine health and status check</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default SettingsPage;
