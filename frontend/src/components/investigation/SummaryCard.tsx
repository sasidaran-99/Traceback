import React from 'react';
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
} from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { formatDate } from '../../utils/formatters';
import type { AgentInvestigationStatus } from '../../api/types';

interface SummaryCardProps {
  investigationId: string;
  reportId?: string;
  serviceName: string;
  environment: string;
  status: AgentInvestigationStatus | string;
  summary: string;
  primaryRootCause?: string;
  createdAt: string;
  isFallback?: boolean;
}

export const SummaryCard: React.FC<SummaryCardProps> = ({
  investigationId,
  reportId,
  serviceName,
  environment,
  status,
  summary,
  primaryRootCause,
  createdAt,
  isFallback,
}) => {
  const [copiedId, setCopiedId] = React.useState(false);

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
    if (norm === 'CONFIRMED') return <CheckCircle2 className="w-5 h-5 text-emerald-400" />;
    if (norm === 'PROBABLE') return <AlertTriangle className="w-5 h-5 text-amber-400" />;
    return <HelpCircle className="w-5 h-5 text-slate-400" />;
  };

  return (
    <div className="rounded-xl border border-dark-700 bg-dark-900/90 shadow-lg p-5">
      {/* Top Metadata Strip */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-dark-700/80">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-dark-950 border border-dark-700 text-xs font-mono text-dark-300">
            <span className="text-dark-500 font-semibold">ID:</span>
            <span>{investigationId}</span>
            <button
              onClick={copyId}
              className="ml-1 text-dark-400 hover:text-dark-100 transition-colors"
              title="Copy Investigation ID"
            >
              {copiedId ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          </div>

          <div className="flex items-center gap-1 text-xs font-mono text-dark-300 px-2 py-0.5 rounded bg-dark-800 border border-dark-700">
            <Server className="w-3 h-3 text-dark-400" />
            <span>{serviceName}</span>
          </div>

          <div className="flex items-center gap-1 text-xs font-mono text-dark-300 px-2 py-0.5 rounded bg-dark-800 border border-dark-700">
            <Layers className="w-3 h-3 text-dark-400" />
            <span className="capitalize">{environment}</span>
          </div>

          <div className="flex items-center gap-1 text-xs text-dark-400">
            <Clock className="w-3 h-3" />
            <span>{formatDate(createdAt)}</span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {isFallback && (
            <span
              className="text-[11px] font-mono px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30"
              title="Generated using deterministic rules because Bifrost AI Gateway was offline or unconfigured"
            >
              DETERMINISTIC FALLBACK
            </span>
          )}
          {reportId && (
            <span className="text-[11px] font-mono text-dark-500 hidden sm:inline">
              Report: {reportId}
            </span>
          )}
          <StatusPill status={status} size="md" />
        </div>
      </div>

      {/* Main Verdict & Summary */}
      <div className="mt-4 grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-3">
          <div>
            <h2 className="text-xs font-mono uppercase tracking-wider text-dark-400 mb-1 flex items-center gap-1.5">
              <Cpu className="w-3.5 h-3.5 text-brand-amber" />
              Investigation Summary
            </h2>
            <p className="text-sm text-dark-100 font-sans leading-relaxed">
              {summary || 'Investigation completed with deterministic correlation analysis.'}
            </p>
          </div>

          {primaryRootCause && (
            <div className="p-3 rounded-lg border border-amber-500/20 bg-amber-500/5">
              <span className="text-xs font-mono uppercase tracking-wider text-amber-400 font-semibold block mb-1">
                Primary Suspected Cause
              </span>
              <p className="text-xs font-mono text-dark-200 leading-relaxed">
                {primaryRootCause}
              </p>
            </div>
          )}
        </div>

        {/* Verdict Callout */}
        <div className="rounded-lg border border-dark-700 bg-dark-950 p-4 flex flex-col justify-between">
          <div>
            <div className="flex items-center gap-2 mb-2">
              {getVerdictIcon(status)}
              <span className="text-xs font-mono font-bold uppercase tracking-wider text-dark-200">
                Grounding Verdict: {status}
              </span>
            </div>
            <p className="text-xs text-dark-400 leading-relaxed">
              {status === 'CONFIRMED'
                ? 'Strong deterministic evidence links the suspect change directly to the observed crash stack with zero unrefuted counter-evidence.'
                : status === 'PROBABLE'
                ? 'High probability root cause identified, but manual inspection of surrounding dependencies or concurrency is recommended.'
                : 'Available telemetry is insufficient or inconclusive to establish causal proof. Honest abstention enforced.'}
            </p>
          </div>
          <div className="mt-3 pt-3 border-t border-dark-800 text-[11px] text-dark-500 font-mono">
            Grounding invariant: Claims strictly verified against local evidence registry.
          </div>
        </div>
      </div>
    </div>
  );
};
