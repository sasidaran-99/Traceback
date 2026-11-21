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
      <div className="p-4 rounded-xl border border-[#1F262E] bg-[#141A20] text-xs text-[#828D9E]">
        No confidence breakdown metrics available for this hypothesis.
      </div>
    );
  }

  const isWorkingTree = breakdown.scoreReasons?.some((r) =>
    r.toLowerCase().includes('filesystem') || r.toLowerCase().includes('working tree') || r.toLowerCase().includes('git head')
  );

  const terms = isWorkingTree
    ? [
        {
          key: 'T',
          name: 'Filesystem Discrepancy (T)',
          value: breakdown.temporalPoints,
          max: 30,
          color: 'bg-[#7FA3C4]',
          description: 'Tracked files physically missing from working directory but present in index',
        },
        {
          key: 'O',
          name: 'Git HEAD Comparison (O)',
          value: breakdown.overlapPoints,
          max: 30,
          color: 'bg-[#4FA88A]',
          description: 'Integrity check confirming missing files exist in HEAD commit tree',
        },
        {
          key: 'A',
          name: 'Git History Corroboration (A)',
          value: breakdown.agreementPoints,
          max: 25,
          color: 'bg-[#7FA3C4]',
          description: 'Corroboration confirming zero committed file deletions in repository history',
        },
        {
          key: 'C',
          name: 'Contradiction Penalty (C)',
          value: breakdown.contradictionPenalty,
          max: 30,
          color: 'bg-[#C86A6A]',
          description: 'Penalties for contradictory commits, conflicting staging states, or unverified claims',
          isPenalty: true,
        },
      ]
    : [
        {
          key: 'T',
          name: 'Temporal Proximity (T)',
          value: breakdown.temporalPoints,
          max: 30,
          color: 'bg-[#7FA3C4]',
          description: 'Changes occurring immediately prior to crash (within incident window)',
        },
        {
          key: 'O',
          name: 'Code / Config Overlap (O)',
          value: breakdown.overlapPoints,
          max: 30,
          color: 'bg-[#4FA88A]',
          description: 'Exact file/line stack trace intersection or configuration key delta',
        },
        {
          key: 'A',
          name: 'Independent Agreement (A)',
          value: breakdown.agreementPoints,
          max: 25,
          color: 'bg-[#7FA3C4]',
          description: 'Multiple distinct telemetry streams (CI, commit message keywords, repeated logs)',
        },
        {
          key: 'C',
          name: 'Contradiction Penalty (C)',
          value: breakdown.contradictionPenalty,
          max: 30,
          color: 'bg-[#C86A6A]',
          description: 'Penalties for cosmetic changes, superseded keys, or post-failure events',
          isPenalty: true,
        },
      ];

  return (
    <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-5 space-y-5 shadow-lg">
      {/* Formula & Overall Score Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-[#1F262E]">
        <div>
          <div className="flex items-center gap-2">
            <TrendingUp className="w-4 h-4 text-[#7FA3C4]" />
            <h3 className="text-xs font-mono uppercase tracking-wider text-[#E4E8EE] font-bold">
              Deterministic Scoring Formula
            </h3>
          </div>
          <p className="text-xs font-mono text-[#A7B0BC] mt-1">
            {isWorkingTree
              ? 'S = T + O + A - C  |  Working-Tree Forensics Mode (Capped at \u2264 85% due to unobserved OS process)'
              : 'S = T + O + A - C  |  Normalized = max(0, S) / 85.0'}
          </p>
        </div>

        <div className="flex items-center gap-4">
          <div className="text-right">
            <span className="text-[10px] uppercase font-mono text-[#6B7585] block">Raw Score</span>
            <span className="font-mono text-sm font-bold text-[#E4E8EE] tabular-nums">
              {breakdown.rawScore?.toFixed(1) || '0.0'} pts
            </span>
          </div>
          <div className="h-6 w-px bg-[#2A323C]" />
          <div className="text-right">
            <span className="text-[10px] font-mono text-[#6B7585] block">Heuristic confidence score</span>
            <span className="font-mono text-base font-bold text-[#7FA3C4] tabular-nums">
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
            <div key={term.key} className="p-3.5 rounded-lg border border-[#1F262E] bg-[#0C1014] space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono font-medium text-[#A7B0BC]">{term.name}</span>
                <span
                  className={`font-mono text-xs font-bold tabular-nums ${
                    term.isPenalty && term.value > 0 ? 'text-[#C86A6A]' : 'text-[#E4E8EE]'
                  }`}
                >
                  {term.isPenalty && term.value > 0 ? `-${term.value}` : `+${term.value}`} / {term.max}
                </span>
              </div>

              {/* Progress bar */}
              <div className="w-full h-1.5 bg-[#1A2129] rounded-full overflow-hidden">
                <div
                  className={`h-full rounded-full ${term.color}`}
                  style={{ width: `${pct}%` }}
                />
              </div>

              <p className="text-[11px] text-[#6B7585] leading-snug">{term.description}</p>
            </div>
          );
        })}
      </div>

      {/* Reasons Lists */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs font-mono">
        {/* Score reasons */}
        <div className="p-3.5 rounded-lg border border-[#1F262E] bg-[#0C1014]">
          <span className="text-[#4FA88A] text-[11px] font-semibold uppercase tracking-wider block mb-2">
            Positive Scoring Factors ({breakdown.scoreReasons?.length || 0})
          </span>
          {breakdown.scoreReasons && breakdown.scoreReasons.length > 0 ? (
            <ul className="space-y-1.5 text-[#A7B0BC]">
              {breakdown.scoreReasons.map((r, i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <span className="text-[#4FA88A] shrink-0">+</span>
                  <span className="leading-relaxed">{r}</span>
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-[#6B7585] italic">No positive factors awarded</span>
          )}
        </div>

        {/* Penalty reasons */}
        <div className="p-3.5 rounded-lg border border-[#1F262E] bg-[#0C1014]">
          <span className="text-[#C86A6A] text-[11px] font-semibold uppercase tracking-wider block mb-2">
            Penalties Applied ({breakdown.penaltyReasons?.length || 0})
          </span>
          {breakdown.penaltyReasons && breakdown.penaltyReasons.length > 0 ? (
            <ul className="space-y-1.5 text-[#A7B0BC]">
              {breakdown.penaltyReasons.map((p, i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <span className="text-[#C86A6A] shrink-0">-</span>
                  <span className="leading-relaxed">{p}</span>
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-[#6B7585] italic">No penalties applied (clean hypothesis)</span>
          )}
        </div>
      </div>

      {/* Epistemological Caveat Banner */}
      <div className="p-3 rounded-lg border-l-[3px] border-l-[#C9A24A] border-t border-r border-b border-[#1F262E] bg-[#141A20] text-xs text-[#A7B0BC] flex items-start gap-2.5">
        <AlertTriangle className="w-4 h-4 text-[#C9A24A] shrink-0 mt-0.5" />
        <p className="leading-relaxed font-sans text-xs">
          <strong className="font-semibold text-[#E4E8EE]">Decision-Support Notice:</strong> The heuristic confidence score ranks candidate hypotheses based on deterministic factors (temporal proximity, code/config overlap, independent agreement, and contradiction penalties). It represents the relative strength of code/config and temporal correlation among available commits. It does <em>not</em> represent an empirical probability of failure causation or proof of active production deployment.
        </p>
      </div>
    </div>
  );
};
