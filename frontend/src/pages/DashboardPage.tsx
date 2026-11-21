import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldAlert,
  PlusCircle,
  Database,
  Terminal,
  Activity,
  RefreshCw,
  ShieldCheck,
  AlertTriangle,
  ArrowRight,
} from 'lucide-react';
import { StatsOverview } from '../components/dashboard/StatsOverview';
import { RecentInvestigations } from '../components/dashboard/RecentInvestigations';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { VerdictBadge } from '../components/shared/VerdictBadge';
import { investigationApi } from '../api/investigationApi';
import type { InvestigationAgentReport, InvestigationResponse } from '../api/types';
import {
  AuthoritativeInvestigationItem,
  buildAuthoritativeInvestigations,
  calculateVerdictMetrics,
} from '../utils/verdictAggregation';
import { useHealth } from '../hooks/useHealth';

export const DashboardPage: React.FC = () => {
  const [investigations, setInvestigations] = useState<InvestigationResponse[]>([]);
  const [agentReports, setAgentReports] = useState<InvestigationAgentReport[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [verdictFilter, setVerdictFilter] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const { health } = useHealth();

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

  const authoritativeItems: AuthoritativeInvestigationItem[] = React.useMemo(() => {
    return buildAuthoritativeInvestigations(investigations, agentReports);
  }, [investigations, agentReports]);

  const metrics = React.useMemo(() => {
    return calculateVerdictMetrics(authoritativeItems);
  }, [authoritativeItems]);

  const needsAttentionItems = React.useMemo(() => {
    return authoritativeItems.filter((inv) => {
      const isFailed = inv.verdict === 'FAILED' || inv.rawInvestigation?.status === 'FAILED';
      const isPending = inv.verdict === 'REPORT_PENDING' || inv.verdict === 'PENDING';
      const isSuspicious =
        inv.verdict === 'CONFIRMED' &&
        (inv.rawInvestigation?.claimsCount ?? 0) === 0 &&
        (inv.report?.supportingEvidenceIds?.length ?? 0) === 0;
      return isFailed || isPending || isSuspicious;
    });
  }, [authoritativeItems]);

  const needsReviewCount = needsAttentionItems.length;

  return (
    <div className="space-y-5 font-sans">
      {/* Single Compact Header: Overview + data-bound summary line */}
      <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-3 pb-4 border-b border-[#1F262E]">
        <div>
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-mono font-bold text-[#E4E8EE] tracking-tight">
              Triage Workspace
            </h2>
            <div className="text-xs font-mono text-[#B0B8C4] flex items-center gap-1.5">
              <span>{authoritativeItems.length} investigations</span>
              <span className="text-[#828D9E]">·</span>
              <span className="text-[#4FA88A]">{metrics.confirmedCount} confirmed</span>
              <span className="text-[#828D9E]">·</span>
              <span className="text-[#C9A24A]">{metrics.probableCount} probable</span>
              <span className="text-[#828D9E]">·</span>
              <span className="text-[#E5A84B] font-semibold">{needsReviewCount} needs review</span>
            </div>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
              v{health?.version || '2.0.0'}
            </span>
          </div>
          <p className="text-xs text-[#828D9E] mt-1 font-sans">
            Don't just tell developers that something broke — prove where, when, and why it occurred with deterministic code-level correlation.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={loadData}
            disabled={isLoading}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-xs font-mono text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
            title="Refresh dashboard telemetry"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
            <span>Sync</span>
          </button>
        </div>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="py-12">
          <LoadingSpinner label="Querying backend investigation registry..." />
        </div>
      )}

      {/* Backend Alert State */}
      {error && (
        <div className="p-3.5 rounded-lg border border-[#C86A6A]/30 bg-[#141A20] text-xs text-[#C86A6A] flex items-center justify-between border-l-[3px] border-l-[#C86A6A]">
          <div className="flex items-center gap-2">
            <ShieldAlert className="w-4 h-4 shrink-0" />
            <span><strong className="font-mono">Backend Connection Alert:</strong> {error}</span>
          </div>
          <button
            onClick={loadData}
            className="font-mono underline text-xs hover:text-[#E4E8EE]"
          >
            Retry
          </button>
        </div>
      )}

      {/* 2-Column Forensic Triage Workspace */}
      {!isLoading && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          {/* Main Column (8 cols): Primary Triage Table & Engineering Grounding Principles */}
          <div className="lg:col-span-8 space-y-5">
            {/* Direct Needs Attention Section for Investigations Requiring Review */}
            {needsAttentionItems.length > 0 && (
              <div className="rounded-lg border border-[#C9A24A]/40 bg-[#1A1813] p-4 space-y-3 border-l-4 border-l-[#C9A24A] shadow-xs">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 text-[#C9A24A] shrink-0" />
                    <h3 className="text-xs font-mono font-bold text-[#E4E8EE] tracking-tight uppercase">
                      Needs Attention ({needsAttentionItems.length})
                    </h3>
                  </div>
                  <span className="text-[11px] font-mono text-[#828D9E]">
                    Pending review or uncorroborated hypotheses
                  </span>
                </div>

                <div className="divide-y divide-[#2A241A] text-xs">
                  {needsAttentionItems.slice(0, 3).map((item) => (
                    <div
                      key={item.investigationId}
                      className="py-2 flex items-center justify-between gap-3 font-mono"
                    >
                      <div className="flex items-center gap-2.5 truncate">
                        <span className="text-[#E4E8EE] font-semibold truncate">
                          {item.serviceName}
                        </span>
                        <span className="text-[11px] text-[#828D9E] capitalize truncate">
                          ({item.environment})
                        </span>
                        <VerdictBadge verdict={item.verdict} size="sm" />
                        <span className="text-[11px] text-[#B0B8C4] truncate hidden md:inline">
                          {item.primaryRootCause || 'Awaiting agent synthesis'}
                        </span>
                      </div>

                      <Link
                        to={`/investigations/${item.investigationId}`}
                        className="inline-flex items-center gap-1 text-[11px] text-[#7FA3C4] hover:text-[#E4E8EE] shrink-0 ml-2"
                      >
                        <span>Review</span>
                        <ArrowRight className="w-3 h-3" />
                      </Link>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {authoritativeItems.length > 0 ? (
              <RecentInvestigations
                authoritativeItems={authoritativeItems}
                investigations={investigations}
                agentReports={agentReports}
                verdictFilter={verdictFilter}
                onVerdictFilterChange={setVerdictFilter}
                searchQuery={searchQuery}
                onSearchQueryChange={setSearchQuery}
              />
            ) : (
              <div className="rounded-lg border border-[#2A323C] bg-[#141A20] p-8 text-center space-y-3">
                <div className="w-10 h-10 rounded-lg bg-[#1A2129] border border-[#2A323C] mx-auto flex items-center justify-center text-[#7FA3C4]">
                  <Terminal className="w-5 h-5" />
                </div>
                <div className="max-w-md mx-auto space-y-1">
                  <h3 className="text-sm font-semibold font-mono text-[#E4E8EE]">
                    No Investigations Recorded Yet
                  </h3>
                  <p className="text-xs text-[#828D9E] leading-relaxed">
                    Start an autonomous failure investigation by providing a local Git repository path and crash logs or stack traces.
                  </p>
                </div>
                <div className="pt-2">
                  <Link
                    to="/new"
                    className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-md text-xs font-sans font-medium bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors"
                  >
                    <PlusCircle className="w-3.5 h-3.5" />
                    <span>Start First Investigation</span>
                  </Link>
                </div>
              </div>
            )}

            {/* Engineering Grounding Principles Footer Strip */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs font-mono pt-1">
              <div className="p-3.5 rounded-lg border border-[#1F262E] bg-[#141A20] space-y-1.5">
                <div className="flex items-center gap-2 text-[#7FA3C4] font-semibold text-xs">
                  <Database className="w-3.5 h-3.5" />
                  <span>Deterministic Scoring</span>
                </div>
                <p className="text-[#828D9E] text-[11px] font-sans leading-relaxed">
                  Hypothesis ranking is grounded strictly in code and config changes: $S = T + O + A - C$.
                </p>
              </div>

              <div className="p-3.5 rounded-lg border border-[#1F262E] bg-[#141A20] space-y-1.5">
                <div className="flex items-center gap-2 text-[#4FA88A] font-semibold text-xs">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  <span>Cryptographic Registry</span>
                </div>
                <p className="text-[#828D9E] text-[11px] font-sans leading-relaxed">
                  Every piece of evidence is tracked with SHA-256 payload checksums in the PostgreSQL evidence store.
                </p>
              </div>

              <div className="p-3.5 rounded-lg border border-[#1F262E] bg-[#141A20] space-y-1.5">
                <div className="flex items-center gap-2 text-[#7FA3C4] font-semibold text-xs">
                  <Activity className="w-3.5 h-3.5" />
                  <span>Sequential Auditing</span>
                </div>
                <p className="text-[#828D9E] text-[11px] font-sans leading-relaxed">
                  Every step documents deterministic ingestion, research queries, LLM reasoning, and claim grounding.
                </p>
              </div>
            </div>
          </div>

          {/* Right Column (4 cols): Contextual Inspector Panel */}
          <div className="lg:col-span-4 space-y-4">
            <StatsOverview
              authoritativeItems={authoritativeItems}
              investigations={investigations}
              agentReports={agentReports}
              metrics={metrics}
              activeVerdictFilter={verdictFilter}
              onSelectVerdictFilter={setVerdictFilter}
            />
          </div>
        </div>
      )}
    </div>
  );
};
