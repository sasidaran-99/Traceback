import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldAlert,
  PlusCircle,
  FileSearch,
  Sparkles,
  Database,
  Terminal,
  Activity,
} from 'lucide-react';
import { StatsOverview } from '../components/dashboard/StatsOverview';
import { RecentInvestigations } from '../components/dashboard/RecentInvestigations';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { investigationApi } from '../api/investigationApi';
import type { InvestigationAgentReport, InvestigationResponse } from '../api/types';

export const DashboardPage: React.FC = () => {
  const [investigations, setInvestigations] = useState<InvestigationResponse[]>([]);
  const [agentReports, setAgentReports] = useState<InvestigationAgentReport[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadData = async () => {
    setIsLoading(true);
    try {
      const [invs, reports] = await Promise.all([
        investigationApi.getAllInvestigations().catch(() => []),
        investigationApi.getAllAgentReports().catch(() => []),
      ]);
      setInvestigations(invs);
      setAgentReports(reports);
      setError(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to fetch dashboard data');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6">
      {/* Product Hero Banner */}
      <div className="rounded-2xl border border-dark-700 bg-gradient-to-r from-dark-900 via-dark-850 to-dark-900 p-6 shadow-xl relative overflow-hidden">
        <div className="relative z-10 max-w-3xl space-y-3">
          <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded-full bg-amber-500/10 border border-amber-500/30 text-brand-amber font-mono text-xs">
            <Sparkles className="w-3.5 h-3.5" />
            <span>Autonomous Software Investigation Platform</span>
          </div>

          <h2 className="text-xl sm:text-2xl font-bold font-sans text-dark-100 tracking-tight leading-snug">
            Don't just tell developers that something broke.{' '}
            <span className="text-brand-amber">Investigate what happened, explain why, and show the evidence.</span>
          </h2>

          <p className="text-xs sm:text-sm text-dark-300 font-sans leading-relaxed">
            Traceback reconstructs multi-system events across Git history, CI/CD pipelines, runtime stack traces, and application logs into a verified, cryptographically audited evidence dossier.
          </p>

          <div className="pt-2 flex flex-wrap items-center gap-3">
            <Link
              to="/new"
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-mono font-semibold bg-brand-amber text-dark-950 hover:bg-amber-400 transition-colors shadow-md"
            >
              <PlusCircle className="w-4 h-4" />
              <span>Launch New Investigation</span>
            </Link>

            <Link
              to="/history"
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-mono font-medium bg-dark-800 text-dark-200 hover:bg-dark-750 transition-colors border border-dark-700"
            >
              <FileSearch className="w-4 h-4 text-dark-400" />
              <span>Review Evidence Records</span>
            </Link>
          </div>
        </div>
      </div>

      {/* Loading state */}
      {isLoading && <LoadingSpinner label="Querying backend investigation registry..." />}

      {/* Error state */}
      {error && (
        <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-500/10 text-xs text-rose-300">
          <span className="font-semibold font-mono">Backend Connection Alert:</span> {error}
        </div>
      )}

      {/* Real Statistics */}
      {!isLoading && (
        <StatsOverview investigations={investigations} agentReports={agentReports} />
      )}

      {/* Recent Investigations or Empty State */}
      {!isLoading && (
        <>
          {investigations.length > 0 ? (
            <RecentInvestigations investigations={investigations} />
          ) : (
            <div className="rounded-2xl border border-dark-700 bg-dark-900/60 p-8 text-center space-y-4">
              <div className="w-12 h-12 rounded-2xl bg-dark-800 border border-dark-700 mx-auto flex items-center justify-center text-brand-amber">
                <Terminal className="w-6 h-6" />
              </div>
              <div className="max-w-md mx-auto space-y-1.5">
                <h3 className="text-sm font-semibold font-mono text-dark-100">
                  No Investigations Recorded Yet
                </h3>
                <p className="text-xs text-dark-400 leading-relaxed">
                  Start an autonomous failure investigation by providing a local Git repository path and crash logs or stack traces.
                </p>
              </div>
              <div>
                <Link
                  to="/new"
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-mono font-medium bg-brand-amber text-dark-950 hover:bg-amber-400 transition-colors shadow-sm"
                >
                  <PlusCircle className="w-3.5 h-3.5" />
                  <span>Start First Investigation</span>
                </Link>
              </div>
            </div>
          )}
        </>
      )}

      {/* Core Principles Strip */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
        <div className="p-4 rounded-xl border border-dark-700/80 bg-dark-900/50 space-y-2">
          <div className="flex items-center gap-2 text-brand-amber font-semibold text-xs">
            <Database className="w-4 h-4" />
            <span>Deterministic Grounding</span>
          </div>
          <p className="text-dark-400 text-[11px] font-sans leading-relaxed">
            Hypothesis ranking is driven by verifiable code and config deltas ($S = T + O + A - C$), not LLM guesswork.
          </p>
        </div>

        <div className="p-4 rounded-xl border border-dark-700/80 bg-dark-900/50 space-y-2">
          <div className="flex items-center gap-2 text-emerald-400 font-semibold text-xs">
            <ShieldAlert className="w-4 h-4" />
            <span>Cryptographic Proof</span>
          </div>
          <p className="text-dark-400 text-[11px] font-sans leading-relaxed">
            Every cited piece of evidence is tracked in the registry with SHA-256 payload checksums.
          </p>
        </div>

        <div className="p-4 rounded-xl border border-dark-700/80 bg-dark-900/50 space-y-2">
          <div className="flex items-center gap-2 text-blue-400 font-semibold text-xs">
            <Activity className="w-4 h-4" />
            <span>Transparent Auditing</span>
          </div>
          <p className="text-dark-400 text-[11px] font-sans leading-relaxed">
            Sequential agent steps document research queries, LLM reasoning, and validation claims.
          </p>
        </div>
      </div>
    </div>
  );
};
