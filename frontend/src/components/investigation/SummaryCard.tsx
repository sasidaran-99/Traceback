import React, { useState } from 'react';
import {
  Server,
  Layers,
  Clock,
  Cpu,
  CheckCircle2,
  AlertTriangle,
  HelpCircle,
  Copy,
  Check,
  ShieldCheck,
  ShieldAlert,
  AlertCircle,
} from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { Badge } from '../shared/Badge';
import { formatDate } from '../../utils/formatters';
import type { AgentInvestigationStatus } from '../../api/types';

export interface SummaryCardProps {
  investigationId: string;
  reportId?: string;
  serviceName: string;
  environment: string;
  investigationMode?: string;
  status: AgentInvestigationStatus | string;
  summary: string;
  primaryRootCause?: string;
  createdAt: string;
  isFallback?: boolean;
  supportingEvidenceCount?: number;
  contradictoryEvidenceCount?: number;
  candidateScore?: number;
  /** @deprecated Kept for backward compatibility */
  investigationScore?: number;
  heuristicScore?: number;
}

export const SummaryCard: React.FC<SummaryCardProps> = ({
  investigationId,
  reportId,
  serviceName,
  environment,
  investigationMode,
  status,
  summary,
  primaryRootCause,
  createdAt,
  isFallback,
  supportingEvidenceCount,
  contradictoryEvidenceCount,
  candidateScore,
  investigationScore,
  heuristicScore,
}) => {
  const [copiedId, setCopiedId] = useState(false);
  const scoreValue =
    candidateScore !== undefined
      ? candidateScore
      : heuristicScore !== undefined
      ? heuristicScore
      : investigationScore;

  const copyId = async () => {
    try {
      await navigator.clipboard.writeText(investigationId);
      setCopiedId(true);
      setTimeout(() => setCopiedId(false), 2000);
    } catch {
      // ignore
    }
  };

  const getVerdictIcon = (st: string) => {
    const norm = st.toUpperCase();
    if (norm === 'CONFIRMED') return <CheckCircle2 className="w-5 h-5 text-[#4FA88A]" />;
    if (norm === 'PROBABLE') return <AlertTriangle className="w-5 h-5 text-[#C9A24A]" />;
    return <HelpCircle className="w-5 h-5 text-[#6B7585]" />;
  };

  const getRootCauseBorderClass = (st: string) => {
    const norm = st.toUpperCase();
    if (norm === 'CONFIRMED' || norm === 'SUPPORTED') return 'border-l-[#4FA88A]';
    if (norm === 'PROBABLE' || norm === 'PARTIALLY_SUPPORTED' || norm === 'PROPOSED') return 'border-l-[#C9A24A]';
    return 'border-l-[#7C8796]';
  };

  // Visual review flag if confirmed with low evidence (does not change verdict)
  const isSuspiciousLowEvidence =
    status.toUpperCase() === 'CONFIRMED' &&
    supportingEvidenceCount !== undefined &&
    supportingEvidenceCount < 1;

  return (
    <div className="rounded-xl border border-[#2A323C] bg-[#141A20] shadow-xl p-5 space-y-4">
      {/* Top Metadata Strip */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-[#1F262E]">
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Investigation ID with copy button */}
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-[#0C1014] border border-[#1F262E] text-xs font-mono text-[#A7B0BC]">
            <span className="text-[#6B7585] font-semibold">ID:</span>
            <span className="text-[#E4E8EE] font-mono">{investigationId}</span>
            <button
              onClick={copyId}
              className="ml-1 text-[#6B7585] hover:text-[#E4E8EE] transition-colors focus:outline-none"
              title="Copy Investigation ID"
              aria-label="Copy Investigation ID"
            >
              {copiedId ? <Check className="w-3.5 h-3.5 text-[#4FA88A]" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          </div>

          <div className="flex items-center gap-1 text-xs font-mono text-[#A7B0BC] px-2.5 py-1 rounded-md bg-[#1A2129] border border-[#1F262E]">
            <Server className="w-3.5 h-3.5 text-[#6B7585]" />
            <span className="font-semibold text-[#E4E8EE]">{serviceName}</span>
          </div>

          <div className="flex items-center gap-1 text-xs font-mono text-[#A7B0BC] px-2.5 py-1 rounded-md bg-[#1A2129] border border-[#1F262E]">
            <Layers className="w-3.5 h-3.5 text-[#6B7585]" />
            <span className="capitalize text-[#E4E8EE]">{environment}</span>
          </div>

          {investigationMode && (
            <Badge variant="blue" mono size="sm">
              {investigationMode.replace(/_/g, ' ')}
            </Badge>
          )}

          <div className="flex items-center gap-1 text-xs text-[#6B7585] font-mono">
            <Clock className="w-3.5 h-3.5" />
            <span>{formatDate(createdAt)}</span>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          {isFallback && (
            <span
              className="text-[11px] font-mono px-2.5 py-0.5 rounded bg-[#1A2129] text-[#C9A24A] border border-[#2A323C] font-semibold"
              title="Generated using deterministic rules because Bifrost AI Gateway was offline or unconfigured"
            >
              DETERMINISTIC FALLBACK
            </span>
          )}
          {reportId && (
            <span className="text-[11px] font-mono text-[#6B7585] hidden sm:inline">
              Report: {reportId}
            </span>
          )}
          <StatusPill status={status} size="md" />
        </div>
      </div>

      {/* Advisory Review Warning */}
      {isSuspiciousLowEvidence && (
        <div className="p-3 rounded-lg border-l-[3px] border-l-[#C9A24A] border-t border-r border-b border-[#1F262E] bg-[#141A20] text-xs text-[#A7B0BC] flex items-start gap-2.5">
          <AlertCircle className="w-4 h-4 text-[#C9A24A] shrink-0 mt-0.5" />
          <p className="leading-relaxed font-sans text-xs">
            <strong className="font-semibold text-[#E4E8EE]">Review Advisory:</strong> Investigation status is CONFIRMED with zero registered supporting evidence records. Verify telemetry pipeline ingestion.
          </p>
        </div>
      )}

      {/* Main Verdict & Summary */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 pt-1">
        <div className="lg:col-span-2 space-y-3.5">
          <div>
            <h2 className="text-xs font-mono uppercase tracking-wider text-[#6B7585] mb-1.5 flex items-center gap-1.5 font-bold">
              <Cpu className="w-3.5 h-3.5 text-[#7FA3C4]" />
              Investigation Summary
            </h2>
            <p className="text-sm text-[#E4E8EE] font-sans leading-relaxed">
              {summary || 'Investigation completed with deterministic correlation analysis.'}
            </p>
          </div>

          {primaryRootCause && (
            <div className={`p-3.5 rounded-lg border-l-[3px] ${getRootCauseBorderClass(status)} border-t border-r border-b border-[#1F262E] bg-[#141A20] space-y-1`}>
              <span className="text-xs font-mono uppercase tracking-wider text-[#E4E8EE] font-bold block">
                Primary Suspected Root Cause
              </span>
              <p className="text-xs font-mono text-[#A7B0BC] leading-relaxed">
                {primaryRootCause}
              </p>
            </div>
          )}

          {/* Evidence Count Strip */}
          {(supportingEvidenceCount !== undefined || contradictoryEvidenceCount !== undefined) && (
            <div className="flex flex-wrap items-center gap-3 pt-1 text-xs font-mono">
              {supportingEvidenceCount !== undefined && (
                <div className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-[#1A2129] text-[#4FA88A] border border-[#1F262E]">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  <span>
                    Supporting Evidence: <strong>{supportingEvidenceCount} items</strong>
                  </span>
                </div>
              )}
              {contradictoryEvidenceCount !== undefined && (
                <div className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-[#0C1014] text-[#6B7585] border border-[#1F262E]">
                  <ShieldAlert className="w-3.5 h-3.5 text-[#6B7585]" />
                  <span>
                    Contradictory Evidence: <strong>{contradictoryEvidenceCount} items</strong>
                  </span>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Grounding Verdict Callout */}
        <div className="rounded-lg border border-[#1F262E] bg-[#0C1014] p-4 flex flex-col justify-between space-y-4">
          <div>
            <div className="flex items-center gap-2 mb-2">
              {getVerdictIcon(status)}
              <span className="text-xs font-mono font-bold uppercase tracking-wider text-[#E4E8EE]">
                Grounding Verdict: {status}
              </span>
            </div>
            <p className="text-xs text-[#A7B0BC] leading-relaxed">
              {status === 'CONFIRMED'
                ? 'Strong deterministic evidence links the suspect change or anomaly directly to the observed incident with zero unrefuted counter-evidence.'
                : status === 'PROBABLE'
                ? 'Plausible contributing change identified with strong structural overlap. The evidence supports investigating this change, but does not independently establish deployment or causation.'
                : 'Available telemetry is insufficient or inconclusive to establish causal proof. Honest abstention enforced.'}
            </p>
          </div>

          <div className="pt-3 border-t border-[#1F262E] space-y-2">
            {scoreValue !== undefined ? (
              <div className="text-xs font-mono flex items-center justify-between">
                <span className="text-[#6B7585]">Candidate Score:</span>
                <span
                  className="text-[#7FA3C4] font-semibold tabular-nums"
                  title="Deterministic ranking score based on heuristic formula (S = T + O + A - C, not a statistical probability)"
                >
                  {(scoreValue * 100).toFixed(0)}% ({scoreValue.toFixed(2)})
                </span>
              </div>
            ) : (
              <div className="text-xs font-mono flex items-center justify-between">
                <span className="text-[#6B7585]">Candidate Score:</span>
                <span className="text-[#6B7585] italic font-mono">
                  {status === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry Insufficient' : 'Unscored'}
                </span>
              </div>
            )}
            <div className="text-[11px] font-mono text-[#6B7585] flex items-center justify-between">
              <span>Telemetry Basis:</span>
              <span className="text-[#A7B0BC]">Git &amp; Working Tree</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
