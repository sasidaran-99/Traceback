import React from 'react';
import {
  ShieldCheck,
  AlertTriangle,
  Activity,
  AlertOctagon,
  ArrowRight,
  Flame,
  Server,
  Cpu,
  Database,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import type { InvestigationAgentReport, InvestigationResponse } from '../../api/types';
import {
  AuthoritativeInvestigationItem,
  VerdictMetrics,
  buildAuthoritativeInvestigations,
  calculateVerdictMetrics,
} from '../../utils/verdictAggregation';
import { VerdictBadge } from '../shared/VerdictBadge';
import { useHealth } from '../../hooks/useHealth';

interface StatsOverviewProps {
  investigations?: InvestigationResponse[];
  agentReports?: InvestigationAgentReport[];
  authoritativeItems?: AuthoritativeInvestigationItem[];
  metrics?: VerdictMetrics;
  hideFlaggedSection?: boolean;
  activeVerdictFilter?: string;
  onSelectVerdictFilter?: (filter: string) => void;
}

export const StatsOverview: React.FC<StatsOverviewProps> = ({
  investigations = [],
  agentReports = [],
  authoritativeItems,
  metrics,
  hideFlaggedSection = false,
  activeVerdictFilter,
  onSelectVerdictFilter,
}) => {
  const { isOnline } = useHealth();

  const items: AuthoritativeInvestigationItem[] = React.useMemo(() => {
    return authoritativeItems || buildAuthoritativeInvestigations(investigations, agentReports);
  }, [authoritativeItems, investigations, agentReports]);

  const effectiveMetrics: VerdictMetrics = React.useMemo(() => {
    if (metrics) return metrics;
    return calculateVerdictMetrics(items);
  }, [metrics, items]);

  const total = effectiveMetrics.totalAnalyzed;

  const formatVerdictSubtitle = (m: VerdictMetrics): string => {
    const parts: string[] = [];
    if (m.probableCount > 0) parts.push(`${m.probableCount} Probable`);
    if (m.insufficientCount > 0) parts.push(`${m.insufficientCount} Insufficient`);
    let text = parts.length > 0 ? parts.join(' | ') : 'No hypotheses evaluated';
    if (m.failedCount > 0) {
      text += ` | ${m.failedCount} Failed`;
    }
    return text;
  };

  // Find investigations requiring operational attention (visual review only)
  const needsAttention = React.useMemo(() => {
    return items.filter((inv) => {
      const isFailed = inv.verdict === 'FAILED' || inv.rawInvestigation?.status === 'FAILED';
      const isPending = inv.verdict === 'REPORT_PENDING' || inv.verdict === 'PENDING';
      const isSuspicious =
        inv.verdict === 'CONFIRMED' &&
        (inv.rawInvestigation?.claimsCount ?? 0) === 0 &&
        (inv.report?.supportingEvidenceIds?.length ?? 0) === 0;
      return isFailed || isPending || isSuspicious;
    });
  }, [items]);

  // Distribution percentages
  const confirmedPct = total > 0 ? (effectiveMetrics.confirmedCount / total) * 100 : 0;
  const probablePct = total > 0 ? (effectiveMetrics.probableCount / total) * 100 : 0;
  const insufficientPct = total > 0 ? (effectiveMetrics.insufficientCount / total) * 100 : 0;
  const otherPct = Math.max(0, 100 - (confirmedPct + probablePct + insufficientPct));

  return (
    <div className="space-y-4 font-sans select-none">
      {/* 2x2 Dense Telemetry Metric Tiles */}
      <div className="grid grid-cols-2 gap-3">
        {/* Tile 1: Total Investigations */}
        <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] hover:border-[#3D4856] transition-all">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-mono uppercase tracking-wider text-[#A7B0BC] font-semibold truncate">
              Investigations Analyzed
            </span>
            <Activity className="w-3.5 h-3.5 text-[#7FA3C4] shrink-0" />
          </div>
          <div className="text-2xl font-bold font-mono text-[#E4E8EE] tracking-tight tabular-nums leading-none">
            {total}
          </div>
          <p className="text-[10px] font-mono text-[#6B7585] mt-1.5 truncate">
            {total > 0 ? `${total} persisted records` : 'No runs yet'}
          </p>
        </div>

        {/* Tile 2: Errors Diagnosed */}
        <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] hover:border-[#3D4856] transition-all">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-mono uppercase tracking-wider text-[#A7B0BC] font-semibold truncate">
              Errors Diagnosed
            </span>
            <Flame className="w-3.5 h-3.5 text-[#C86A6A] shrink-0" />
          </div>
          <div className="text-2xl font-bold font-mono text-[#E4E8EE] tracking-tight tabular-nums leading-none">
            {effectiveMetrics.totalErrors || total}
          </div>
          <p className="text-[10px] font-mono text-[#6B7585] mt-1.5 truncate">
            Multi-line Java crash logs
          </p>
        </div>

        {/* Tile 3: Probable Candidates */}
        <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] hover:border-[#3D4856] transition-all">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-mono uppercase tracking-wider text-[#A7B0BC] font-semibold truncate">
              Probable Candidates
            </span>
            <AlertTriangle className="w-3.5 h-3.5 text-[#C9A24A] shrink-0" />
          </div>
          <div className="text-2xl font-bold font-mono text-[#E4E8EE] tracking-tight tabular-nums leading-none">
            {effectiveMetrics.probableCount}
          </div>
          <p className="text-[10px] font-mono text-[#6B7585] mt-1.5 truncate">
            Strong overlap candidate{effectiveMetrics.probableCount === 1 ? '' : 's'}
          </p>
        </div>

        {/* Tile 4: Agent Root-Cause Verdicts */}
        <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] hover:border-[#3D4856] transition-all">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-mono uppercase tracking-wider text-[#A7B0BC] font-semibold truncate">
              Agent Root-Cause Verdicts
            </span>
            <ShieldCheck className="w-3.5 h-3.5 text-[#4FA88A] shrink-0" />
          </div>
          <div className="text-base font-bold font-mono text-[#E4E8EE] tracking-tight tabular-nums leading-none truncate">
            {effectiveMetrics.confirmedCount} Confirmed
          </div>
          <p className="text-[10px] font-mono text-[#6B7585] mt-1.5 truncate" title={formatVerdictSubtitle(effectiveMetrics)}>
            {formatVerdictSubtitle(effectiveMetrics)}
          </p>
        </div>
      </div>

      {/* Verdict Distribution Section - Segmented Interactive Breakdown */}
      <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-2.5">
        <div className="flex items-center justify-between text-xs font-mono">
          <span className="text-[#B0B8C4] uppercase tracking-wider text-[11px] font-semibold">
            Verdict Distribution Breakdown
          </span>
          <span className="text-[#828D9E] text-[11px] tabular-nums">
            {total} Total Evaluated
          </span>
        </div>

        {/* Segmented Distribution Bar (~6px height) */}
        <div
          className="h-2 w-full bg-[#1A2129] rounded-full overflow-hidden flex border border-[#1F262E] cursor-pointer"
          title="Click any segment to filter the triage table"
        >
          {confirmedPct > 0 && (
            <div
              style={{ width: `${confirmedPct}%` }}
              onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'CONFIRMED' ? 'ALL' : 'CONFIRMED')}
              className={`bg-[#4FA88A] hover:opacity-80 transition-all ${activeVerdictFilter === 'CONFIRMED' ? 'ring-1 ring-white' : ''}`}
              title={`Confirmed: ${effectiveMetrics.confirmedCount} (${confirmedPct.toFixed(1)}%) - Click to filter`}
            />
          )}
          {probablePct > 0 && (
            <div
              style={{ width: `${probablePct}%` }}
              onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'PROBABLE' ? 'ALL' : 'PROBABLE')}
              className={`bg-[#C9A24A] hover:opacity-80 transition-all ${activeVerdictFilter === 'PROBABLE' ? 'ring-1 ring-white' : ''}`}
              title={`Probable: ${effectiveMetrics.probableCount} (${probablePct.toFixed(1)}%) - Click to filter`}
            />
          )}
          {insufficientPct > 0 && (
            <div
              style={{ width: `${insufficientPct}%` }}
              onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'INSUFFICIENT_EVIDENCE' ? 'ALL' : 'INSUFFICIENT_EVIDENCE')}
              className={`bg-[#7C8796] hover:opacity-80 transition-all ${activeVerdictFilter === 'INSUFFICIENT_EVIDENCE' ? 'ring-1 ring-white' : ''}`}
              title={`Insufficient Evidence: ${effectiveMetrics.insufficientCount} (${insufficientPct.toFixed(1)}%) - Click to filter`}
            />
          )}
          {otherPct > 0 && (
            <div
              style={{ width: `${otherPct}%` }}
              onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'PENDING' ? 'ALL' : 'PENDING')}
              className={`bg-[#6E9CC4] hover:opacity-80 transition-all ${activeVerdictFilter === 'PENDING' ? 'ring-1 ring-white' : ''}`}
              title={`Pending: ${otherPct.toFixed(1)}% - Click to filter`}
            />
          )}
        </div>

        {/* Interactive Filter Legend */}
        <div className="grid grid-cols-2 gap-1.5 text-[11px] font-mono pt-1 text-[#828D9E]">
          <button
            type="button"
            onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'CONFIRMED' ? 'ALL' : 'CONFIRMED')}
            className={`flex items-center gap-1.5 px-1.5 py-1 rounded transition-colors text-left ${
              activeVerdictFilter === 'CONFIRMED'
                ? 'bg-[#1A2129] border border-[#4FA88A]/50 text-[#E4E8EE]'
                : 'hover:bg-[#1A2129] hover:text-[#E4E8EE]'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-[#4FA88A] shrink-0" />
            <span className="truncate">Confirmed ({effectiveMetrics.confirmedCount})</span>
          </button>

          <button
            type="button"
            onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'PROBABLE' ? 'ALL' : 'PROBABLE')}
            className={`flex items-center gap-1.5 px-1.5 py-1 rounded transition-colors text-left ${
              activeVerdictFilter === 'PROBABLE'
                ? 'bg-[#1A2129] border border-[#C9A24A]/50 text-[#E4E8EE]'
                : 'hover:bg-[#1A2129] hover:text-[#E4E8EE]'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-[#C9A24A] shrink-0" />
            <span className="truncate">Probable ({effectiveMetrics.probableCount})</span>
          </button>

          <button
            type="button"
            onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'INSUFFICIENT_EVIDENCE' ? 'ALL' : 'INSUFFICIENT_EVIDENCE')}
            className={`flex items-center gap-1.5 px-1.5 py-1 rounded transition-colors text-left ${
              activeVerdictFilter === 'INSUFFICIENT_EVIDENCE'
                ? 'bg-[#1A2129] border border-[#7C8796]/50 text-[#E4E8EE]'
                : 'hover:bg-[#1A2129] hover:text-[#E4E8EE]'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-[#7C8796] shrink-0" />
            <span className="truncate">Insufficient ({effectiveMetrics.insufficientCount})</span>
          </button>

          {(effectiveMetrics.pendingReportCount > 0 || effectiveMetrics.failedCount > 0) && (
            <button
              type="button"
              onClick={() => onSelectVerdictFilter?.(activeVerdictFilter === 'PENDING' ? 'ALL' : 'PENDING')}
              className={`flex items-center gap-1.5 px-1.5 py-1 rounded transition-colors text-left ${
                activeVerdictFilter === 'PENDING'
                  ? 'bg-[#1A2129] border border-[#6E9CC4]/50 text-[#E4E8EE]'
                : 'hover:bg-[#1A2129] hover:text-[#E4E8EE]'
              }`}
            >
              <span className="w-2 h-2 rounded-full bg-[#6E9CC4] shrink-0" />
              <span className="truncate">
                Pending ({effectiveMetrics.pendingReportCount + effectiveMetrics.failedCount})
              </span>
            </button>
          )}
        </div>
      </div>

      {/* Engine & Forensic System Context */}
      <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-2 text-xs font-mono">
        <div className="text-[11px] uppercase tracking-wider text-[#A7B0BC] font-semibold flex items-center justify-between">
          <span>Engine Runtime</span>
          <span className="flex items-center gap-1 text-[10px] text-[#4FA88A]">
            <span className="w-1.5 h-1.5 rounded-full bg-[#4FA88A]" />
            ACTIVE
          </span>
        </div>
        <div className="divide-y divide-[#1F262E] text-[11px]">
          <div className="py-1.5 flex items-center justify-between">
            <span className="text-[#6B7585] flex items-center gap-1.5">
              <Server className="w-3 h-3 text-[#7FA3C4]" />
              Spring Boot Core
            </span>
            <span className="text-[#E4E8EE]">{isOnline ? '8080 : UP' : 'OFFLINE'}</span>
          </div>
          <div className="py-1.5 flex items-center justify-between">
            <span className="text-[#6B7585] flex items-center gap-1.5">
              <Cpu className="w-3 h-3 text-[#7FA3C4]" />
              Bifrost Gateway
            </span>
            <span className="text-[#7FA3C4]">gemini-3.8-flash</span>
          </div>
          <div className="py-1.5 flex items-center justify-between">
            <span className="text-[#6B7585] flex items-center gap-1.5">
              <Database className="w-3 h-3 text-[#7FA3C4]" />
              Registry Storage
            </span>
            <span className="text-[#E4E8EE]">PostgreSQL (traceback_db)</span>
          </div>
        </div>
      </div>

      {/* Flagged Investigation Notice (if not rendered in main column) */}
      {!hideFlaggedSection && needsAttention.length > 0 && (
        <div className="p-3.5 rounded-lg border border-[#2A323C] border-l-[3px] border-l-[#C9A24A] bg-[#141A20] space-y-2">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-1.5 text-[#E4E8EE] font-mono font-semibold">
              <AlertOctagon className="w-3.5 h-3.5 text-[#C9A24A]" />
              <span>Flagged for Review ({needsAttention.length})</span>
            </div>
          </div>

          <div className="divide-y divide-[#1F262E]">
            {needsAttention.slice(0, 3).map((item) => (
              <div key={item.investigationId} className="py-1.5 flex items-center justify-between gap-2 text-xs">
                <div className="flex items-center gap-1.5 font-mono truncate">
                  <span className="text-[#E4E8EE] font-medium truncate">{item.serviceName}</span>
                  <VerdictBadge verdict={item.verdict} size="sm" />
                </div>
                <Link
                  to={`/investigations/${item.investigationId}`}
                  className="text-xs font-mono text-[#7FA3C4] hover:text-[#E4E8EE] flex items-center gap-1 shrink-0 transition-colors"
                >
                  <span>Inspect</span>
                  <ArrowRight className="w-3 h-3" />
                </Link>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
