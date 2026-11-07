import React from 'react';
import { TrendingUp, AlertTriangle } from 'lucide-react';
import type { ConfidenceBreakdown } from '../../api/types';
import { formatConfidence } from '../../utils/formatters';

interface ConfidenceBreakdownViewProps {
  breakdown?: ConfidenceBreakdown | null;
  overallScore?: number;
}

export const ConfidenceBreakdownView: React.FC<ConfidenceBreakdownViewProps> = ({
  breakdown,
  overallScore = 0,
}) => {
  if (!breakdown) {
    return (
      <div className="p-4 rounded-xl border border-dark-700 bg-dark-900/60 text-xs text-dark-400">
        No confidence breakdown metrics available for this hypothesis.
      </div>
    );
  }

  const terms = [
    {
      key: 'T',
      name: 'Temporal Proximity (T)',
      value: breakdown.temporalPoints,
      max: 30,
      color: 'bg-blue-400',
      description: 'Changes occurring immediately prior to crash (within incident window)',
    },
    {
      key: 'O',
      name: 'Code / Config Overlap (O)',
      value: breakdown.overlapPoints,
      max: 30,
      color: 'bg-emerald-400',
      description: 'Exact file/line stack trace intersection or configuration key delta',
    },
    {
      key: 'A',
      name: 'Independent Agreement (A)',
      value: breakdown.agreementPoints,
      max: 25,
      color: 'bg-purple-400',
      description: 'Multiple distinct telemetry streams (CI, commit message keywords, repeated logs)',
    },
    {
      key: 'C',
      name: 'Contradiction Penalty (C)',
      value: breakdown.contradictionPenalty,
      max: 30,
      color: 'bg-rose-400',
      description: 'Penalties for cosmetic changes, superseded keys, or post-failure events',
      isPenalty: true,
    },
  ];

  return (
    <div className="rounded-xl border border-dark-700 bg-dark-900/80 p-5 space-y-5">
      {/* Formula & Overall Score Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-dark-700/80">
        <div>
          <div className="flex items-center gap-2">
            <TrendingUp className="w-4 h-4 text-brand-amber" />
            <h3 className="text-xs font-mono uppercase tracking-wider text-dark-200 font-bold">
              Deterministic Scoring Formula
            </h3>
          </div>
          <p className="text-xs font-mono text-dark-400 mt-0.5">
            S = T + O + A - C &nbsp;|&nbsp; Normalized = max(0, S) / 85.0
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="text-right">
            <span className="text-[10px] uppercase font-mono text-dark-400 block">Raw Score</span>
            <span className="font-mono text-sm font-bold text-dark-200">
              {breakdown.rawScore?.toFixed(1) || '0.0'} pts
            </span>
          </div>
          <div className="h-6 w-px bg-dark-700" />
          <div className="text-right">
            <span className="text-[10px] font-mono text-dark-400 block">Heuristic confidence score</span>
            <span className="font-mono text-base font-bold text-brand-amber">
              {formatConfidence(breakdown.normalizedScore ?? overallScore)}
            </span>
          </div>
        </div>
      </div>

      {/* Breakdown Terms Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {terms.map((term) => {
          const pct = Math.min(100, Math.max(0, (term.value / term.max) * 100));

          return (
            <div key={term.key} className="p-3 rounded-lg border border-dark-700 bg-dark-950/70 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono font-medium text-dark-300">{term.name}</span>
                <span
                  className={`font-mono text-xs font-bold ${
                    term.isPenalty && term.value > 0 ? 'text-rose-400' : 'text-dark-100'
                  }`}
                >
                  {term.isPenalty && term.value > 0 ? `-${term.value}` : `+${term.value}`} / {term.max}
                </span>
              </div>

              {/* Progress bar */}
              <div className="w-full h-1.5 bg-dark-800 rounded-full overflow-hidden">
                <div
                  className={`h-full rounded-full ${term.color}`}
                  style={{ width: `${pct}%` }}
                />
              </div>

              <p className="text-[11px] text-dark-400 leading-tight">{term.description}</p>
            </div>
          );
        })}
      </div>

      {/* Reasons Lists */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs font-mono">
        {/* Score reasons */}
        <div className="p-3 rounded-lg border border-dark-700/80 bg-dark-950/50">
          <span className="text-emerald-400 text-[11px] font-semibold uppercase tracking-wider block mb-2">
            Positive Scoring Factors ({breakdown.scoreReasons?.length || 0})
          </span>
          {breakdown.scoreReasons && breakdown.scoreReasons.length > 0 ? (
            <ul className="space-y-1 text-dark-300">
              {breakdown.scoreReasons.map((r, i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <span className="text-emerald-400 shrink-0">+</span>
                  <span>{r}</span>
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-dark-500 italic">No positive factors awarded</span>
          )}
        </div>

        {/* Penalty reasons */}
        <div className="p-3 rounded-lg border border-dark-700/80 bg-dark-950/50">
          <span className="text-rose-400 text-[11px] font-semibold uppercase tracking-wider block mb-2">
            Penalties Applied ({breakdown.penaltyReasons?.length || 0})
          </span>
          {breakdown.penaltyReasons && breakdown.penaltyReasons.length > 0 ? (
            <ul className="space-y-1 text-dark-300">
              {breakdown.penaltyReasons.map((p, i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <span className="text-rose-400 shrink-0">-</span>
                  <span>{p}</span>
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-dark-500 italic">No penalties applied (clean hypothesis)</span>
          )}
        </div>
      </div>

      {/* Epistemological Caveat Banner */}
      <div className="p-3 rounded-lg border border-amber-500/20 bg-amber-500/5 text-xs text-amber-300/90 flex items-start gap-2.5">
        <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
        <p className="leading-relaxed font-sans text-[11px]">
          <strong className="font-semibold text-amber-300">Decision-Support Notice:</strong> The heuristic confidence score ranks candidate hypotheses based on deterministic factors (temporal proximity, code/config overlap, independent agreement, and contradiction penalties). It represents the relative strength of code/config and temporal correlation among available commits. It does <em>not</em> represent an empirical probability of failure causation or proof of active production deployment.
        </p>
      </div>
    </div>
  );
};
