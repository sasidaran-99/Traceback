import React, { useState } from 'react';
import {
  ChevronDown,
  ChevronUp,
  FileSearch,
  GitCommit,
  ShieldCheck,
  ShieldAlert,
  GitBranch,
  ExternalLink,
} from 'lucide-react';
import { Badge } from '../shared/Badge';
import { StatusPill } from '../shared/StatusPill';
import { formatConfidence } from '../../utils/formatters';
import type { HypothesisResponse } from '../../api/types';

interface HypothesesListProps {
  hypotheses: HypothesisResponse[];
  onSelectHypothesis?: (hypothesis: HypothesisResponse) => void;
  onInspectHypothesis?: (hypothesis: HypothesisResponse) => void;
  onSelectEvidence?: (evidenceId: string) => void;
}

export const HypothesesList: React.FC<HypothesesListProps> = ({
  hypotheses,
  onInspectHypothesis,
  onSelectEvidence,
}) => {
  const [viewMode, setViewMode] = useState<'cards' | 'matrix'>('cards');
  const [expandedId, setExpandedId] = useState<string | null>(
    hypotheses.length > 0 ? (hypotheses[0].id || hypotheses[0].hypothesisId || '0') : null
  );

  const toggleExpand = (id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  if (!hypotheses || hypotheses.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-[#1F262E] bg-[#141A20] text-center text-[#A7B0BC] text-xs space-y-2">
        <FileSearch className="w-6 h-6 mx-auto text-[#6B7585] mb-1" />
        <p className="font-mono text-[#E4E8EE]">
          No persisted hypothesis records are available for this investigation. See the agent&apos;s confirmed forensic conclusion and supporting evidence below.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {/* View Mode Toggle Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded-xl border border-[#1F262E] bg-[#141A20] shadow-xs">
        <div className="flex items-center gap-2">
          <GitBranch className="w-4 h-4 text-[#7FA3C4]" />
          <span className="font-mono text-xs font-semibold text-[#E4E8EE]">
            Candidate Hypotheses ({hypotheses.length})
          </span>
        </div>

        <div className="flex items-center gap-1 font-mono text-xs bg-[#0C1014] p-0.5 rounded-lg border border-[#1F262E]">
          <button
            type="button"
            onClick={() => setViewMode('cards')}
            className={`px-3 py-1 rounded text-xs transition-colors cursor-pointer ${
              viewMode === 'cards'
                ? 'bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C] font-semibold'
                : 'text-[#6B7585] hover:text-[#A7B0BC]'
            }`}
            aria-pressed={viewMode === 'cards'}
          >
            Card View
          </button>
          <button
            type="button"
            onClick={() => setViewMode('matrix')}
            className={`px-3 py-1 rounded text-xs transition-colors cursor-pointer ${
              viewMode === 'matrix'
                ? 'bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C] font-semibold'
                : 'text-[#6B7585] hover:text-[#A7B0BC]'
            }`}
            aria-pressed={viewMode === 'matrix'}
          >
            Comparison Matrix
          </button>
        </div>
      </div>

      {/* MATRIX VIEW */}
      {viewMode === 'matrix' ? (
        <div className="rounded-xl border border-[#1F262E] bg-[#141A20] overflow-hidden shadow-xs">
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs font-mono">
              <thead>
                <tr className="border-b border-[#1F262E] bg-[#0C1014] text-[#6B7585] uppercase text-[10px] tracking-wider">
                  <th className="py-3 px-3 w-12 text-center">Rank</th>
                  <th className="py-3 px-3">Candidate Hypothesis</th>
                  <th className="py-3 px-3 w-28">Status</th>
                  <th className="py-3 px-3 w-28 text-right" title="Heuristic ranking confidence score (prioritization only)">Heuristic Score</th>
                  <th className="py-3 px-2 w-16 text-center" title="Temporal proximity factor (T)">T</th>
                  <th className="py-3 px-2 w-16 text-center" title="Overlap factor (O)">O</th>
                  <th className="py-3 px-2 w-16 text-center" title="Agreement factor (A)">A</th>
                  <th className="py-3 px-2 w-16 text-center" title="Contradiction penalty (C)">C</th>
                  <th className="py-3 px-3 w-28 text-center">Evidence</th>
                  <th className="py-3 px-3">Counter Evidence / Notes</th>
                  <th className="py-3 px-3 w-16 text-center">Inspect</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#1F262E]">
                {hypotheses.map((h, idx) => {
                  const isTop = h.rank === 1;
                  const breakdown = h.confidenceBreakdown || h.breakdown;
                  const suppCount = h.supportingEvidenceIds?.length ?? h.evidenceIds?.length ?? 0;
                  const hypKey = h.id || h.hypothesisId || `hyp-row-${idx}`;

                  // Factor extraction: display em-dash if breakdown or factor is unavailable
                  const tPoints = breakdown?.temporalPoints !== undefined && breakdown?.temporalPoints !== null
                    ? `+${breakdown.temporalPoints}`
                    : '—';
                  const oPoints = breakdown?.overlapPoints !== undefined && breakdown?.overlapPoints !== null
                    ? `+${breakdown.overlapPoints}`
                    : '—';
                  const aPoints = breakdown?.agreementPoints !== undefined && breakdown?.agreementPoints !== null
                    ? `+${breakdown.agreementPoints}`
                    : '—';
                  const cPenalty = breakdown?.contradictionPenalty !== undefined && breakdown?.contradictionPenalty !== null
                    ? `-${Math.abs(breakdown.contradictionPenalty)}`
                    : '—';

                  return (
                    <tr
                      key={hypKey}
                      onClick={() => onInspectHypothesis?.(h)}
                      className={`hover:bg-[#1A2129]/60 transition-colors cursor-pointer ${
                        isTop ? 'bg-[#141A20]' : 'bg-[#141A20]/80'
                      }`}
                    >
                      {/* Rank */}
                      <td className="py-3 px-3 text-center">
                        <span
                          className={`inline-block px-1.5 py-0.5 rounded text-xs font-bold border tabular-nums ${
                            isTop
                              ? 'bg-[#1A2129] text-[#7FA3C4] border-[#2A323C]'
                              : 'bg-[#0C1014] text-[#A7B0BC] border-[#1F262E]'
                          }`}
                        >
                          #{h.rank}
                        </span>
                      </td>

                      {/* Summary & Commit */}
                      <td className="py-3 px-3 font-sans">
                        <div className="font-semibold text-[#E4E8EE] line-clamp-2">
                          {h.summary}
                        </div>
                        {h.commitSha && (
                          <span className="inline-flex items-center gap-1 text-[10px] font-mono text-[#A7B0BC] mt-1 bg-[#0C1014] px-1.5 py-0.2 rounded border border-[#1F262E]">
                            <GitCommit className="w-2.5 h-2.5 text-[#7FA3C4]" />
                            {h.commitSha.substring(0, 7)}
                          </span>
                        )}
                      </td>

                      {/* Status */}
                      <td className="py-3 px-3 whitespace-nowrap">
                        <StatusPill status={h.status || (isTop ? 'CONFIRMED' : 'PROPOSED')} size="sm" />
                      </td>

                      {/* Heuristic Score */}
                      <td className="py-3 px-3 text-right tabular-nums">
                        <span
                          className={`font-bold ${
                            h.confidenceScore >= 0.7
                              ? 'text-[#4FA88A]'
                              : h.confidenceScore >= 0.4
                              ? 'text-[#7FA3C4]'
                              : 'text-[#6B7585]'
                          }`}
                        >
                          {formatConfidence(h.confidenceScore)}
                        </span>
                      </td>

                      {/* Factor T */}
                      <td
                        className={`py-3 px-2 text-center tabular-nums ${
                          tPoints === '—' ? 'text-[#6B7585]' : 'text-[#7FA3C4]'
                        }`}
                        title={tPoints === '—' ? 'Factor unavailable for candidate' : `Temporal proximity: ${tPoints}`}
                      >
                        {tPoints}
                      </td>

                      {/* Factor O */}
                      <td
                        className={`py-3 px-2 text-center tabular-nums ${
                          oPoints === '—' ? 'text-[#6B7585]' : 'text-[#4FA88A]'
                        }`}
                        title={oPoints === '—' ? 'Factor unavailable for candidate' : `Structural overlap: ${oPoints}`}
                      >
                        {oPoints}
                      </td>

                      {/* Factor A */}
                      <td
                        className={`py-3 px-2 text-center tabular-nums ${
                          aPoints === '—' ? 'text-[#6B7585]' : 'text-[#7FA3C4]'
                        }`}
                        title={aPoints === '—' ? 'Factor unavailable for candidate' : `Agreement points: ${aPoints}`}
                      >
                        {aPoints}
                      </td>

                      {/* Factor C */}
                      <td
                        className={`py-3 px-2 text-center tabular-nums ${
                          cPenalty === '—' ? 'text-[#6B7585]' : 'text-[#C86A6A]'
                        }`}
                        title={cPenalty === '—' ? 'Factor unavailable for candidate' : `Contradiction penalty: ${cPenalty}`}
                      >
                        {cPenalty}
                      </td>

                      {/* Supporting Evidence count & pills */}
                      <td className="py-3 px-3 text-center whitespace-nowrap">
                        {suppCount > 0 ? (
                          <div className="flex flex-wrap items-center justify-center gap-1">
                            <span className="text-[#4FA88A] font-bold tabular-nums">{suppCount} items</span>
                            {h.supportingEvidenceIds && h.supportingEvidenceIds.length > 0 && (
                              <div className="flex flex-wrap gap-1 justify-center mt-1">
                                {h.supportingEvidenceIds.slice(0, 2).map((evId) => (
                                  <button
                                    key={evId}
                                    type="button"
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      onSelectEvidence?.(evId);
                                    }}
                                    className="px-1 py-0.2 rounded bg-[#0C1014] border border-[#1F262E] text-[10px] text-[#4FA88A] hover:border-[#4FA88A]/60"
                                    title={`View evidence ${evId}`}
                                  >
                                    {evId}
                                  </button>
                                ))}
                                {h.supportingEvidenceIds.length > 2 && (
                                  <span className="text-[10px] text-[#6B7585]">+{h.supportingEvidenceIds.length - 2}</span>
                                )}
                              </div>
                            )}
                          </div>
                        ) : (
                          <span className="text-[#6B7585] italic text-[11px]">0</span>
                        )}
                      </td>

                      {/* Counter Evidence / Notes */}
                      <td className="py-3 px-3 text-[11px] text-[#A7B0BC] font-sans">
                        {h.counterEvidence ? (
                          <span className="text-[#C86A6A] font-mono text-[11px] line-clamp-2">
                            {h.counterEvidence}
                          </span>
                        ) : (
                          <span className="text-[#6B7585] italic text-[11px]">No counter evidence</span>
                        )}
                      </td>

                      {/* Inspect Action */}
                      <td className="py-3 px-3 text-center">
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            onInspectHypothesis?.(h);
                          }}
                          className="p-1 rounded text-[#7FA3C4] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors"
                          title="Inspect candidate forensic details"
                          aria-label={`Inspect candidate hypothesis #${h.rank}`}
                        >
                          <ExternalLink className="w-3.5 h-3.5" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        /* CARD VIEW */
        hypotheses.map((h, idx) => {
          const hypKey = h.id || h.hypothesisId || `hyp-${idx}`;
          const isExpanded = expandedId === hypKey;
          const isTop = h.rank === 1;

          return (
            <div
              key={hypKey}
              className={`rounded-xl border transition-all ${
                isTop
                  ? 'border-[#2A323C] bg-[#141A20] shadow-md'
                  : 'border-[#1F262E] bg-[#141A20]/80 hover:border-[#2A323C]'
              }`}
            >
              {/* Accordion Trigger Button */}
              <button
                type="button"
                id={`hypothesis-header-${hypKey}`}
                onClick={() => toggleExpand(hypKey)}
                aria-expanded={isExpanded}
                aria-controls={`hypothesis-details-${hypKey}`}
                className="w-full text-left p-4 flex flex-wrap items-center justify-between gap-3 cursor-pointer select-none focus:outline-none focus-visible:ring-1 focus-visible:ring-[#7FA3C4] rounded-t-xl transition-colors"
              >
                <div className="flex items-center gap-3">
                  <span
                    className={`w-7 h-7 rounded-lg font-mono font-bold text-xs flex items-center justify-center border tabular-nums ${
                      isTop
                        ? 'bg-[#1A2129] text-[#7FA3C4] border-[#2A323C] shadow-xs'
                        : 'bg-[#1A2129] text-[#A7B0BC] border-[#1F262E]'
                    }`}
                  >
                    #{h.rank}
                  </span>

                  <div>
                    <h3 className="text-sm font-semibold text-[#E4E8EE] flex items-center gap-2">
                      {h.summary}
                    </h3>
                    <div className="flex flex-wrap items-center gap-2 mt-1">
                      {h.commitSha && (
                        <span className="text-xs font-mono text-[#A7B0BC] flex items-center gap-1 bg-[#0C1014] px-2 py-0.5 rounded border border-[#1F262E]">
                          <GitCommit className="w-3 h-3 text-[#7FA3C4]" />
                          {h.commitSha.substring(0, 7)}
                        </span>
                      )}
                      {h.matchedRules && h.matchedRules.length > 0 && (
                        <span className="text-xs font-mono text-[#6B7585] flex items-center gap-1">
                          <FileSearch className="w-3 h-3 text-[#6B7585]" />
                          Rules: {h.matchedRules.join(', ')}
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-4">
                  {/* Confidence Pill */}
                  <div className="text-right">
                    <div className="flex items-center gap-1.5 justify-end">
                      <span className="text-[11px] font-mono text-[#6B7585]">Heuristic confidence score:</span>
                      <span
                        className={`font-mono text-sm font-bold tabular-nums ${
                          h.confidenceScore >= 0.7
                            ? 'text-[#4FA88A]'
                            : h.confidenceScore >= 0.4
                            ? 'text-[#7FA3C4]'
                            : 'text-[#6B7585]'
                        }`}
                      >
                        {formatConfidence(h.confidenceScore)}
                      </span>
                    </div>
                    <span className="text-[10px] font-mono text-[#6B7585] block">
                      {isTop ? 'Leading candidate' : 'Alternative candidate'}
                    </span>
                  </div>

                  <span
                    className="text-[#6B7585] hover:text-[#E4E8EE] transition-colors p-1 flex items-center"
                    aria-hidden="true"
                  >
                    {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                  </span>
                </div>
              </button>

              {/* Expandable Details */}
              {isExpanded && (
                <div
                  id={`hypothesis-details-${hypKey}`}
                  role="region"
                  aria-labelledby={`hypothesis-header-${hypKey}`}
                  className="px-4 pb-4 pt-1 border-t border-[#1F262E] space-y-3 text-xs"
                >
                  {/* Why this hypothesis is ranked here */}
                  {isTop && (
                    <div className="p-2.5 rounded-lg bg-[#4FA88A]/10 border border-[#4FA88A]/30 text-xs text-[#B0B8C4] flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-[10px] font-bold text-[#4FA88A] uppercase tracking-wider px-1.5 py-0.5 rounded bg-[#4FA88A]/20">
                          Rank #1 Grounding
                        </span>
                        <span className="text-xs font-sans">
                          Top-ranked candidate based on temporal proximity to failure and code overlap.
                        </span>
                      </div>
                    </div>
                  )}

                  <div className="flex items-center justify-between pt-1">
                    {h.description && (
                      <p className="text-[#B0B8C4] font-sans leading-relaxed">
                        {h.description}
                      </p>
                    )}
                    <button
                      type="button"
                      onClick={() => onInspectHypothesis?.(h)}
                      className="ml-auto px-2.5 py-1 rounded bg-[#0C1014] border border-[#2A323C] text-[11px] font-mono text-[#7FA3C4] hover:text-[#E4E8EE] hover:border-[#7FA3C4] transition-colors flex items-center gap-1 cursor-pointer shrink-0"
                    >
                      <ExternalLink className="w-3 h-3" />
                      <span>Inspect Candidate</span>
                    </button>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3 pt-2">
                    {/* Supporting Evidence */}
                    <div className="p-3 rounded-lg bg-[#0C1014] border border-[#1F262E] space-y-2">
                      <span className="text-[#4FA88A] font-mono text-[11px] font-semibold uppercase tracking-wider flex items-center gap-1.5">
                        <ShieldCheck className="w-3.5 h-3.5" />
                        Supporting Evidence ({h.supportingEvidenceIds?.length || 0})
                      </span>
                      {h.supportingEvidenceIds && h.supportingEvidenceIds.length > 0 ? (
                        <div className="flex flex-wrap gap-1.5">
                          {h.supportingEvidenceIds.map((id: string) => (
                            <button
                              key={id}
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                onSelectEvidence?.(id);
                              }}
                              className="px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C] font-mono text-[#4FA88A] text-[11px] hover:text-[#E4E8EE] hover:border-[#4FA88A]/60 focus:outline-none focus-visible:ring-1 focus-visible:ring-[#4FA88A] transition-colors cursor-pointer"
                              title={`Inspect evidence item ${id}`}
                              aria-label={`Inspect evidence item ${id}`}
                            >
                              {id}
                            </button>
                          ))}
                        </div>
                      ) : (
                        <p className="text-[#6B7585] italic text-[11px]">No specific evidence IDs linked</p>
                      )}
                    </div>

                    {/* Counter Evidence */}
                    <div className="p-3 rounded-lg bg-[#0C1014] border border-[#1F262E] space-y-2">
                      <span className="text-[#C86A6A] font-mono text-[11px] font-semibold uppercase tracking-wider flex items-center gap-1.5">
                        <ShieldAlert className="w-3.5 h-3.5" />
                        Counter Evidence
                      </span>
                      {h.counterEvidence ? (
                        <p className="text-[#C86A6A] font-mono text-[11px] leading-relaxed">
                          {h.counterEvidence}
                        </p>
                      ) : (
                        <p className="text-[#6B7585] italic text-[11px]">No contradictory evidence observed</p>
                      )}
                    </div>
                  </div>

                  {/* Structural vs Config overlap badges */}
                  <div className="flex flex-wrap items-center gap-2 pt-2 border-t border-[#1F262E]">
                    <Badge variant={h.hasStructuralOverlap ? 'green' : 'neutral'} mono size="sm">
                      {h.hasStructuralOverlap ? 'Structural Overlap: YES' : 'Structural Overlap: NO'}
                    </Badge>
                    <Badge variant={h.hasConfigOverlap ? 'amber' : 'neutral'} mono size="sm">
                      {h.hasConfigOverlap ? 'Config Delta: YES' : 'Config Delta: NO'}
                    </Badge>
                  </div>
                </div>
              )}
            </div>
          );
        })
      )}
    </div>
  );
};
