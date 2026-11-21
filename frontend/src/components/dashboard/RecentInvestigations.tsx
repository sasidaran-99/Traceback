import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { HelpCircle, ExternalLink, Clock, Search, Filter, ChevronDown, ChevronUp, ArrowUpDown } from 'lucide-react';
import { VerdictBadge } from '../shared/VerdictBadge';
import { TechnicalChip } from '../shared/TechnicalChip';
import { formatConfidence, formatDate } from '../../utils/formatters';
import type { InvestigationAgentReport, InvestigationResponse } from '../../api/types';
import {
  AuthoritativeInvestigationItem,
  buildAuthoritativeInvestigations,
} from '../../utils/verdictAggregation';

interface RecentInvestigationsProps {
  investigations?: InvestigationResponse[];
  agentReports?: InvestigationAgentReport[];
  authoritativeItems?: AuthoritativeInvestigationItem[];
  verdictFilter?: string;
  onVerdictFilterChange?: (filter: string) => void;
  searchQuery?: string;
  onSearchQueryChange?: (q: string) => void;
}

export const RecentInvestigations: React.FC<RecentInvestigationsProps> = ({
  investigations = [],
  agentReports = [],
  authoritativeItems,
  verdictFilter: propVerdictFilter,
  onVerdictFilterChange,
  searchQuery: propSearchQuery,
  onSearchQueryChange,
}) => {
  const navigate = useNavigate();

  // Local filter states if not controlled externally
  const [localVerdictFilter, setLocalVerdictFilter] = useState('ALL');
  const [localSearchQuery, setLocalSearchQuery] = useState('');
  const [sortBy, setSortBy] = useState<'newest' | 'oldest' | 'score'>('newest');
  const [expandedRowIds, setExpandedRowIds] = useState<Record<string, boolean>>({});

  const activeVerdictFilter = propVerdictFilter !== undefined ? propVerdictFilter : localVerdictFilter;
  const setFilter = onVerdictFilterChange || setLocalVerdictFilter;

  const activeSearchQuery = propSearchQuery !== undefined ? propSearchQuery : localSearchQuery;
  const setSearch = onSearchQueryChange || setLocalSearchQuery;

  const items: AuthoritativeInvestigationItem[] = React.useMemo(() => {
    if (authoritativeItems) return authoritativeItems;
    return buildAuthoritativeInvestigations(investigations, agentReports);
  }, [authoritativeItems, investigations, agentReports]);

  if (!items || items.length === 0) {
    return null;
  }

  // Format relative timestamp
  const getRelativeTime = (iso?: string | null) => {
    if (!iso) return 'Recent';
    try {
      const ms = Date.now() - new Date(iso).getTime();
      const mins = Math.floor(ms / 60000);
      if (mins < 1) return 'Just now';
      if (mins < 60) return `${mins}m ago`;
      const hrs = Math.floor(mins / 60);
      if (hrs < 24) return `${hrs}h ago`;
      const days = Math.floor(hrs / 24);
      return `${days}d ago`;
    } catch {
      return 'Recent';
    }
  };

  const toggleRowExpansion = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExpandedRowIds((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  // Filter and sort items
  const filteredAndSortedItems = React.useMemo(() => {
    let result = items.filter((inv) => {
      // Verdict filter
      if (activeVerdictFilter !== 'ALL') {
        if (activeVerdictFilter === 'PENDING') {
          if (inv.verdict !== 'PENDING' && inv.verdict !== 'REPORT_PENDING') return false;
        } else if (inv.verdict !== activeVerdictFilter) {
          return false;
        }
      }

      // Search filter
      if (activeSearchQuery.trim()) {
        const q = activeSearchQuery.toLowerCase();
        const matchesId = inv.investigationId.toLowerCase().includes(q);
        const matchesService = inv.serviceName.toLowerCase().includes(q);
        const matchesEnv = inv.environment?.toLowerCase().includes(q);
        const matchesCause = (inv.primaryRootCause || inv.report?.primarySuspectedRootCause || '').toLowerCase().includes(q);
        if (!matchesId && !matchesService && !matchesEnv && !matchesCause) return false;
      }

      return true;
    });

    // Sorting
    result.sort((a, b) => {
      if (sortBy === 'score') {
        const scoreA =
          a.report?.deterministicConfidenceBreakdown?.normalizedScore ??
          a.topHypothesis?.confidenceScore ??
          0;
        const scoreB =
          b.report?.deterministicConfidenceBreakdown?.normalizedScore ??
          b.topHypothesis?.confidenceScore ??
          0;
        return scoreB - scoreA;
      }
      const timeA = new Date(a.completedAt || a.startedAt || 0).getTime();
      const timeB = new Date(b.completedAt || b.startedAt || 0).getTime();
      return sortBy === 'newest' ? timeB - timeA : timeA - timeB;
    });

    return result;
  }, [items, activeVerdictFilter, activeSearchQuery, sortBy]);

  return (
    <div className="rounded-lg border border-[#2A323C] bg-[#141A20] overflow-hidden shadow-xs font-sans">
      {/* Header Bar */}
      <div className="px-4 py-3 border-b border-[#1F262E] flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-xs font-semibold text-[#E4E8EE] font-mono tracking-tight uppercase">
            Recent Failure Investigations
          </h3>
          <p className="text-[11px] text-[#828D9E] mt-0.5">
            Persisted records from completed investigations
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Link
            to="/history"
            className="text-xs font-mono text-[#7FA3C4] hover:text-[#E4E8EE] flex items-center gap-1 transition-colors"
          >
            <span>View Archive ({items.length})</span>
          </Link>
        </div>
      </div>

      {/* Triage Workspace Filter & Sort Strip */}
      <div className="px-4 py-2.5 bg-[#0E1216] border-b border-[#1F262E] flex flex-wrap items-center justify-between gap-3 text-xs">
        {/* Segmented Verdict Filter */}
        <div className="flex items-center gap-1 font-mono">
          <span className="text-[11px] text-[#828D9E] mr-1 flex items-center gap-1">
            <Filter className="w-3 h-3 text-[#7FA3C4]" />
            Verdict:
          </span>
          {[
            { id: 'ALL', label: 'All', count: items.length },
            { id: 'CONFIRMED', label: 'Confirmed', count: items.filter((i) => i.verdict === 'CONFIRMED').length },
            { id: 'PROBABLE', label: 'Probable', count: items.filter((i) => i.verdict === 'PROBABLE').length },
            { id: 'INSUFFICIENT_EVIDENCE', label: 'Insufficient', count: items.filter((i) => i.verdict === 'INSUFFICIENT_EVIDENCE').length },
            { id: 'PENDING', label: 'Pending', count: items.filter((i) => i.verdict === 'PENDING' || i.verdict === 'REPORT_PENDING').length },
          ].map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setFilter(tab.id === activeVerdictFilter ? 'ALL' : tab.id)}
              className={`px-2 py-0.5 rounded text-[11px] transition-colors cursor-pointer ${
                activeVerdictFilter === tab.id
                  ? 'bg-[#1A2129] text-[#E4E8EE] border border-[#7FA3C4]/50 font-semibold'
                  : 'text-[#828D9E] hover:text-[#E4E8EE] hover:bg-[#141A20]'
              }`}
            >
              <span>{tab.label}</span>
              <span className="ml-1 text-[10px] opacity-70">({tab.count})</span>
            </button>
          ))}
        </div>

        {/* Search & Sort Controls */}
        <div className="flex items-center gap-2">
          {/* Quick Search */}
          <div className="relative">
            <input
              type="text"
              value={activeSearchQuery}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Filter by service or ID..."
              className="pl-7 pr-2.5 py-1 rounded bg-[#141A20] border border-[#2A323C] text-[11px] text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none focus:border-[#7FA3C4] w-36 sm:w-48 font-mono transition-colors"
            />
            <Search className="w-3 h-3 text-[#828D9E] absolute left-2 top-2" />
          </div>

          {/* Sort Selector */}
          <div className="flex items-center gap-1 font-mono text-[11px] text-[#828D9E]">
            <ArrowUpDown className="w-3 h-3 text-[#7FA3C4]" />
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as 'newest' | 'oldest' | 'score')}
              className="bg-[#141A20] border border-[#2A323C] text-[11px] text-[#E4E8EE] px-2 py-1 rounded focus:outline-none focus:border-[#7FA3C4]"
            >
              <option value="newest">Newest</option>
              <option value="oldest">Oldest</option>
              <option value="score">Highest Score</option>
            </select>
          </div>
        </div>
      </div>

      {/* Table Container */}
      <div className="overflow-x-auto">
        {filteredAndSortedItems.length === 0 ? (
          <div className="p-8 text-center text-xs text-[#828D9E] font-mono">
            No investigations match the current filter criteria ({activeVerdictFilter !== 'ALL' ? `Verdict: ${activeVerdictFilter}` : ''}
            {activeSearchQuery ? `, Search: "${activeSearchQuery}"` : ''}).
            <button
              onClick={() => {
                setFilter('ALL');
                setSearch('');
              }}
              className="ml-2 text-[#7FA3C4] underline hover:text-[#E4E8EE]"
            >
              Reset filters
            </button>
          </div>
        ) : (
          <table className="w-full text-left text-xs">
            <thead className="bg-[#0E1216] border-b border-[#2A323C] text-[#828D9E] font-mono uppercase text-[11px] tracking-wider">
              <tr>
                <th className="py-2.5 px-4 font-semibold">Investigation ID</th>
                <th className="py-2.5 px-4 font-semibold">Service &amp; Env</th>
                <th className="py-2.5 px-4 font-semibold">Verdict / Status</th>
                <th className="py-2.5 px-4 font-semibold">Analyzed</th>
                <th className="py-2.5 px-4 font-semibold">Top Root-Cause Hypothesis</th>
                <th className="py-2.5 px-4 font-semibold">
                  <span
                    className="inline-flex items-center gap-1 cursor-help border-b border-dotted border-[#2A323C]"
                    title="Deterministic ranking score based on heuristic formula (S = T + O + A - C, not a statistical probability)"
                  >
                    <span>Heuristic Score</span>
                    <HelpCircle className="w-3 h-3 text-[#828D9E]" />
                  </span>
                </th>
                <th className="py-2.5 px-4 text-right font-semibold">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#1F262E] text-[#E4E8EE] font-mono">
              {filteredAndSortedItems.slice(0, 10).map((inv) => {
                // Authoritative hypothesis summary or root cause
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
                    : inv.verdict === 'REPORT_PENDING'
                    ? 'Investigation completed / Report pending'
                    : inv.verdict === 'FAILED'
                    ? 'Investigation failed'
                    : 'Inconclusive telemetry');

                // Clean one-line summary
                const cleanRootCause = rootCause.replace(/^[A-Z_]+:\s*/, '');
                const updatedTime = inv.completedAt || inv.startedAt;
                const isExpanded = !!expandedRowIds[inv.investigationId];

                const candidateScore: number | undefined =
                  inv.report?.deterministicConfidenceBreakdown?.normalizedScore ??
                  inv.topHypothesis?.confidenceScore ??
                  inv.rawInvestigation?.topHypothesis?.confidenceScore ??
                  undefined;

                return (
                  <tr
                    key={inv.investigationId}
                    onClick={() => navigate(`/investigations/${inv.investigationId}`)}
                    className="hover:bg-[#1A2129] cursor-pointer transition-colors group"
                    title={`Click to open full dossier for ${inv.investigationId}`}
                  >
                    {/* ID */}
                    <td className="py-2.5 px-4" onClick={(e) => e.stopPropagation()}>
                      <TechnicalChip
                        value={inv.investigationId}
                        truncateLength={8}
                        variant="code"
                      />
                    </td>

                    {/* Service & Env */}
                    <td className="py-2.5 px-4">
                      <div className="font-semibold text-[#E4E8EE] font-sans text-xs group-hover:text-[#7FA3C4] transition-colors">
                        {inv.serviceName}
                      </div>
                      <span className="text-[#828D9E] text-[11px] font-mono capitalize">
                        {inv.environment}
                      </span>
                    </td>

                    {/* Verdict */}
                    <td className="py-2.5 px-4">
                      <VerdictBadge
                        verdict={inv.verdict}
                        size="sm"
                      />
                    </td>

                    {/* Analyzed Stats */}
                    <td className="py-2.5 px-4 text-[#B0B8C4] text-[11px]">
                      <span className="tabular-nums">{inv.commitsAnalyzed} commits</span>
                      <span className="text-[#828D9E] block tabular-nums">{inv.errorsDetected} errors</span>
                    </td>

                    {/* Leading Hypothesis: Up to two lines with expandable full text & tooltip */}
                    <td className="py-2.5 px-4 max-w-xs xl:max-w-md">
                      <div className="relative">
                        <p
                          className={`text-[#B0B8C4] font-sans text-xs leading-snug transition-all ${
                            isExpanded ? '' : 'line-clamp-2'
                          }`}
                          title={cleanRootCause}
                        >
                          {cleanRootCause}
                        </p>
                        {cleanRootCause.length > 90 && (
                          <button
                            type="button"
                            onClick={(e) => toggleRowExpansion(inv.investigationId, e)}
                            className="text-[10px] text-[#7FA3C4] hover:text-[#E4E8EE] mt-0.5 inline-flex items-center gap-0.5"
                          >
                            <span>{isExpanded ? 'Show less' : 'Show full'}</span>
                            {isExpanded ? <ChevronUp className="w-2.5 h-2.5" /> : <ChevronDown className="w-2.5 h-2.5" />}
                          </button>
                        )}
                      </div>
                      {updatedTime && (
                        <span
                          className="text-[10px] text-[#828D9E] flex items-center gap-1 mt-0.5"
                          title={formatDate(updatedTime)}
                        >
                          <Clock className="w-2.5 h-2.5" />
                          {getRelativeTime(updatedTime)}
                        </span>
                      )}
                    </td>

                    {/* Score */}
                    <td className="py-2.5 px-4">
                      {candidateScore !== undefined ? (
                        <span
                          className={`font-bold text-xs tabular-nums ${
                            candidateScore >= 0.7
                              ? 'text-[#4FA88A]'
                              : candidateScore >= 0.4
                              ? 'text-[#7FA3C4]'
                              : 'text-[#828D9E]'
                          }`}
                          title={`Heuristic candidate score: ${formatConfidence(candidateScore)} (deterministic ranking metric S = T + O + A - C, not a statistical probability)`}
                        >
                          {formatConfidence(candidateScore)}
                        </span>
                      ) : (
                        <span
                          className="text-[#828D9E] text-xs italic font-mono"
                          title={inv.verdict === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry insufficient to compute candidate score' : 'No deterministic candidate score recorded'}
                        >
                          Unscored
                        </span>
                      )}
                    </td>

                    {/* Action */}
                    <td className="py-2.5 px-4 text-right">
                      <Link
                        to={`/investigations/${inv.investigationId}`}
                        onClick={(e) => e.stopPropagation()}
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
        )}
      </div>
    </div>
  );
};
