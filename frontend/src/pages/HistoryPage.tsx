import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  History,
  Search,
  Filter,
  ExternalLink,
  RefreshCw,
  Clock,
  AlertCircle,
  X,
  ShieldCheck,
  Cpu,
  Layers,
  Server,
} from 'lucide-react';
import { investigationApi } from '../api/investigationApi';
import type { InvestigationAgentReport, InvestigationResponse } from '../api/types';
import {
  AuthoritativeInvestigationItem,
  buildAuthoritativeInvestigations,
} from '../utils/verdictAggregation';
import { VerdictBadge } from '../components/shared/VerdictBadge';
import { TechnicalChip } from '../components/shared/TechnicalChip';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { formatDate, formatConfidence } from '../utils/formatters';

export const HistoryPage: React.FC = () => {
  const [investigations, setInvestigations] = useState<InvestigationResponse[]>([]);
  const [agentReports, setAgentReports] = useState<InvestigationAgentReport[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Filters
  const [searchTerm, setSearchTerm] = useState('');
  const [verdictFilter, setVerdictFilter] = useState('ALL');
  const [envFilter, setEnvFilter] = useState('ALL');

  // Selected row for contextual inspector
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const fetchHistory = async () => {
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
      setError(err instanceof Error ? err.message : 'Failed to load investigation history');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchHistory();
  }, []);

  const items: AuthoritativeInvestigationItem[] = React.useMemo(() => {
    return buildAuthoritativeInvestigations(investigations, agentReports);
  }, [investigations, agentReports]);

  // Unique environments
  const environments = React.useMemo(() => {
    const set = new Set<string>();
    items.forEach((i) => {
      if (i.environment) set.add(i.environment);
    });
    return Array.from(set);
  }, [items]);

  // Filtered investigations
  const filtered = React.useMemo(() => {
    return items.filter((inv) => {
      const matchesSearch =
        searchTerm.trim() === '' ||
        inv.investigationId.toLowerCase().includes(searchTerm.toLowerCase()) ||
        inv.serviceName.toLowerCase().includes(searchTerm.toLowerCase()) ||
        (inv.primaryRootCause && inv.primaryRootCause.toLowerCase().includes(searchTerm.toLowerCase())) ||
        (inv.topHypothesis?.summary && inv.topHypothesis.summary.toLowerCase().includes(searchTerm.toLowerCase()));

      const matchesVerdict =
        verdictFilter === 'ALL' ||
        inv.verdict === verdictFilter ||
        (verdictFilter === 'PENDING' && (inv.verdict === 'REPORT_PENDING' || inv.verdict === 'PENDING'));

      const matchesEnv = envFilter === 'ALL' || inv.environment === envFilter;

      return matchesSearch && matchesVerdict && matchesEnv;
    });
  }, [items, searchTerm, verdictFilter, envFilter]);

  const selectedItem = React.useMemo(() => {
    if (!selectedId) return null;
    return items.find((i) => i.investigationId === selectedId) || null;
  }, [items, selectedId]);

  const filterTabs = [
    { id: 'ALL', label: 'All' },
    { id: 'CONFIRMED', label: 'Confirmed' },
    { id: 'PROBABLE', label: 'Probable' },
    { id: 'INSUFFICIENT_EVIDENCE', label: 'Insufficient Evidence' },
    { id: 'PENDING', label: 'Pending' },
  ];

  return (
    <div className="space-y-4 font-sans select-none">
      {/* Compact Operational Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-3 border-b border-[#1F262E]">
        <div>
          <h2 className="text-base font-mono font-bold text-[#E4E8EE] flex items-center gap-2">
            <History className="w-4 h-4 text-[#7FA3C4]" />
            Investigation Archive
          </h2>
          <p className="text-xs text-[#6B7585] mt-0.5 font-sans">
            Complete persistent record of autonomous forensic investigations.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span className="text-xs font-mono text-[#A7B0BC]">
            Showing <strong className="text-[#E4E8EE]">{filtered.length}</strong> of {items.length} records
          </span>
          <button
            onClick={fetchHistory}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-xs font-mono text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
            title="Sync records with database"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
            <span>Sync</span>
          </button>
        </div>
      </div>

      {/* Filter and Search Bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-2.5 rounded-lg border border-[#2A323C] bg-[#141A20]">
        {/* Search */}
        <div className="relative flex-1 min-w-[240px]">
          <Search className="w-3.5 h-3.5 text-[#6B7585] absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search by ID, service name, or summary keyword..."
            className="w-full pl-8 pr-3 py-1.5 rounded-md border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#6B7585] focus:outline-none focus:border-[#7FA3C4]"
          />
          {searchTerm && (
            <button
              onClick={() => setSearchTerm('')}
              className="absolute right-2.5 top-2 text-[#6B7585] hover:text-[#E4E8EE]"
              title="Clear search"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>

        {/* Environment Filter */}
        {environments.length > 1 && (
          <div className="flex items-center gap-1.5 text-xs font-mono">
            <Filter className="w-3.5 h-3.5 text-[#6B7585]" />
            <select
              value={envFilter}
              onChange={(e) => setEnvFilter(e.target.value)}
              className="px-2.5 py-1.5 rounded-md border border-[#2A323C] bg-[#0C1014] text-xs font-mono text-[#A7B0BC] focus:outline-none focus:border-[#7FA3C4]"
            >
              <option value="ALL">All Environments</option>
              {environments.map((env) => (
                <option key={env} value={env}>
                  {env}
                </option>
              ))}
            </select>
          </div>
        )}

        {/* Verdict Filter Chips */}
        <div className="flex flex-wrap items-center gap-1 font-mono text-xs">
          {filterTabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setVerdictFilter(tab.id)}
              className={`px-2.5 py-1 rounded-md transition-colors text-xs ${
                verdictFilter === tab.id
                  ? 'bg-[#1A2129] text-[#E4E8EE] border border-[#7FA3C4]/50 font-medium'
                  : 'text-[#A7B0BC] hover:text-[#E4E8EE] hover:bg-[#1A2129]/60'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      {isLoading ? (
        <div className="py-12">
          <LoadingSpinner label="Fetching investigations from backend registry..." />
        </div>
      ) : error ? (
        <div className="p-3.5 rounded-lg border border-[#C86A6A]/30 bg-[#141A20] text-xs text-[#C86A6A] flex items-center gap-2 border-l-[3px] border-l-[#C86A6A]">
          <AlertCircle className="w-4 h-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="p-10 rounded-lg border border-[#2A323C] bg-[#141A20] text-center text-[#6B7585] text-xs space-y-2">
          <p className="font-mono text-[#A7B0BC]">No investigations match your active filter criteria.</p>
          <button
            onClick={() => {
              setSearchTerm('');
              setVerdictFilter('ALL');
              setEnvFilter('ALL');
            }}
            className="text-xs font-mono text-[#7FA3C4] underline hover:text-[#E4E8EE]"
          >
            Reset Filters
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
          {/* Table Container - Takes full width or 8 cols if inspector is open */}
          <div className={selectedItem ? 'lg:col-span-8' : 'lg:col-span-12'}>
            <div className="rounded-lg border border-[#2A323C] bg-[#141A20] overflow-hidden shadow-xs">
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="bg-[#0E1216] border-b border-[#2A323C] text-[#6B7585] font-mono uppercase text-[11px] tracking-wider">
                    <tr>
                      <th className="py-2.5 px-4 font-semibold">Investigation ID</th>
                      <th className="py-2.5 px-4 font-semibold">Service</th>
                      <th className="py-2.5 px-4 font-semibold">Environment</th>
                      <th className="py-2.5 px-4 font-semibold">Status / Verdict</th>
                      <th className="py-2.5 px-4 font-semibold">Commits / Errors</th>
                      <th className="py-2.5 px-4 font-semibold">Leading Root-Cause Hypothesis</th>
                      <th className="py-2.5 px-4 font-semibold">
                        <span
                          className="cursor-help border-b border-dotted border-[#2A323C]"
                          title="Deterministic ranking score based on heuristic formula (S = T + O + A - C, not a statistical probability)"
                        >
                          Candidate Score
                        </span>
                      </th>
                      <th className="py-2.5 px-4 font-semibold">Updated</th>
                      <th className="py-2.5 px-4 font-semibold text-right">Dossier</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#1F262E] text-[#E4E8EE] font-mono">
                    {filtered.map((inv) => {
                      const rootCause =
                        inv.primaryRootCause ||
                        inv.report?.primarySuspectedRootCause ||
                        inv.topHypothesis?.summary ||
                        (inv.verdict === 'CONFIRMED'
                          ? 'Confirmed working tree forensic anomaly'
                          : inv.verdict === 'PROBABLE'
                          ? 'Probable contributing change identified'
                          : inv.verdict === 'INSUFFICIENT_EVIDENCE'
                          ? 'Inconclusive telemetry / Honest abstention'
                          : 'Inconclusive telemetry');

                      const cleanRootCause = rootCause.replace(/^[A-Z_]+:\s*/, '');

                      const candidateScore: number | undefined =
                        inv.report?.deterministicConfidenceBreakdown?.normalizedScore ??
                        inv.topHypothesis?.confidenceScore ??
                        inv.rawInvestigation?.topHypothesis?.confidenceScore ??
                        undefined;

                      const updatedTime = inv.completedAt || inv.startedAt;
                      const isSelected = selectedId === inv.investigationId;

                      return (
                        <tr
                          key={inv.investigationId}
                          onClick={() => setSelectedId(isSelected ? null : inv.investigationId)}
                          className={`transition-colors cursor-pointer ${
                            isSelected
                              ? 'bg-[#1A2129] border-l-2 border-l-[#7FA3C4]'
                              : 'hover:bg-[#1A2129]/60'
                          }`}
                        >
                          {/* ID */}
                          <td className="py-2.5 px-4">
                            <TechnicalChip
                              value={inv.investigationId}
                              truncateLength={8}
                              variant="code"
                            />
                          </td>

                          {/* Service */}
                          <td className="py-2.5 px-4 font-semibold text-[#E4E8EE] font-sans">
                            {inv.serviceName}
                          </td>

                          {/* Environment */}
                          <td className="py-2.5 px-4 text-[#A7B0BC] capitalize font-mono text-[11px]">
                            {inv.environment}
                          </td>

                          {/* Status / Verdict */}
                          <td className="py-2.5 px-4">
                            <VerdictBadge
                              verdict={inv.verdict}
                              size="sm"
                            />
                          </td>

                          {/* Commits / Errors */}
                          <td className="py-2.5 px-4 text-[#B0B8C4] text-[11px]">
                            <span className="tabular-nums">{inv.commitsAnalyzed} commits</span>
                            <span className="text-[#828D9E] block tabular-nums">{inv.errorsDetected} errors</span>
                          </td>

                          {/* Leading Root-Cause */}
                          <td className="py-2.5 px-4 max-w-sm">
                            <p
                              className="line-clamp-2 text-[#B0B8C4] font-sans text-xs leading-snug"
                              title={cleanRootCause}
                            >
                              {cleanRootCause}
                            </p>
                          </td>

                          {/* Candidate Score */}
                          <td className="py-2.5 px-4 font-mono text-xs">
                            {candidateScore !== undefined ? (
                              <span
                                className={`font-bold text-xs block tabular-nums ${
                                  candidateScore >= 0.7
                                    ? 'text-[#4FA88A]'
                                    : candidateScore >= 0.4
                                    ? 'text-[#7FA3C4]'
                                    : 'text-[#6B7585]'
                                }`}
                                title={`Heuristic candidate score: ${formatConfidence(candidateScore)} (deterministic ranking metric S = T + O + A - C, not a statistical probability)`}
                              >
                                {formatConfidence(candidateScore)}
                              </span>
                            ) : (
                              <span
                                className="text-[#6B7585] text-xs italic font-mono block"
                                title={inv.verdict === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry insufficient to compute candidate score' : 'No deterministic candidate score recorded'}
                              >
                                Unscored
                              </span>
                            )}
                          </td>

                          {/* Date */}
                          <td className="py-2.5 px-4 text-[#6B7585] text-[11px] whitespace-nowrap" title={formatDate(updatedTime)}>
                            <span className="flex items-center gap-1">
                              <Clock className="w-3 h-3 text-[#6B7585]" />
                              {formatDate(updatedTime)}
                            </span>
                          </td>

                          {/* Dossier Link */}
                          <td className="py-2.5 px-4 text-right" onClick={(e) => e.stopPropagation()}>
                            <Link
                              to={`/investigations/${inv.investigationId}`}
                              className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-[#1A2129] hover:bg-[#222B35] text-xs font-mono text-[#7FA3C4] hover:text-[#E4E8EE] border border-[#2A323C] transition-colors"
                              title="Open full investigation dossier"
                            >
                              <span>Dossier</span>
                              <ExternalLink className="w-3 h-3" />
                            </Link>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* Selected Row Inspector Panel (Desktop 4 cols) */}
          {selectedItem && (
            <div className="lg:col-span-4 p-4 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-4">
              <div className="flex items-center justify-between pb-3 border-b border-[#1F262E]">
                <div className="flex items-center gap-2">
                  <Cpu className="w-4 h-4 text-[#7FA3C4]" />
                  <span className="text-xs font-mono font-semibold text-[#E4E8EE]">
                    Record Inspector
                  </span>
                </div>
                <button
                  onClick={() => setSelectedId(null)}
                  className="text-[#6B7585] hover:text-[#E4E8EE] p-0.5 rounded"
                  title="Close inspector"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              <div className="space-y-3 text-xs font-sans">
                <div>
                  <span className="text-[11px] font-mono text-[#6B7585] uppercase tracking-wider block mb-1">
                    Investigation ID
                  </span>
                  <div className="font-mono text-xs text-[#E4E8EE] break-all bg-[#0C1014] p-2 rounded border border-[#1F262E]">
                    {selectedItem.investigationId}
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-2 text-xs font-mono">
                  <div className="p-2 rounded bg-[#0C1014] border border-[#1F262E]">
                    <span className="text-[10px] text-[#6B7585] block">Service</span>
                    <span className="text-[#E4E8EE] font-semibold flex items-center gap-1 mt-0.5">
                      <Server className="w-3 h-3 text-[#7FA3C4]" />
                      {selectedItem.serviceName}
                    </span>
                  </div>
                  <div className="p-2 rounded bg-[#0C1014] border border-[#1F262E]">
                    <span className="text-[10px] text-[#6B7585] block">Environment</span>
                    <span className="text-[#E4E8EE] capitalize flex items-center gap-1 mt-0.5">
                      <Layers className="w-3 h-3 text-[#7FA3C4]" />
                      {selectedItem.environment}
                    </span>
                  </div>
                </div>

                <div>
                  <span className="text-[11px] font-mono text-[#6B7585] uppercase tracking-wider block mb-1">
                    Grounding Verdict
                  </span>
                  <div className="flex items-center gap-2">
                    <VerdictBadge verdict={selectedItem.verdict} size="md" />
                    {selectedItem.verdict === 'CONFIRMED' && (
                      <span className="text-[11px] font-mono text-[#4FA88A] flex items-center gap-1">
                        <ShieldCheck className="w-3.5 h-3.5" />
                        Deterministic causal proof
                      </span>
                    )}
                  </div>
                </div>

                <div>
                  <span className="text-[11px] font-mono text-[#6B7585] uppercase tracking-wider block mb-1">
                    Suspected Root Cause
                  </span>
                  <p className="text-xs text-[#A7B0BC] bg-[#0C1014] p-2.5 rounded border border-[#1F262E] leading-relaxed">
                    {selectedItem.primaryRootCause ||
                      selectedItem.report?.primarySuspectedRootCause ||
                      selectedItem.topHypothesis?.summary ||
                      'Inconclusive telemetry / Honest abstention'}
                  </p>
                </div>

                <div className="pt-2">
                  <Link
                    to={`/investigations/${selectedItem.investigationId}`}
                    className="w-full flex items-center justify-center gap-2 py-2 rounded-md bg-[#3A6C96] hover:bg-[#457CAB] text-white text-xs font-mono font-medium transition-colors shadow-xs"
                  >
                    <span>Open Full Case File Dossier</span>
                    <ExternalLink className="w-3.5 h-3.5" />
                  </Link>
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
