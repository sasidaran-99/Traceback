import React, { useState } from 'react';
import {
  CheckCircle,
  XCircle,
  ChevronDown,
  ChevronUp,
  FileSearch,
  ListChecks,
} from 'lucide-react';
import { Badge } from '../shared/Badge';
import { formatConfidence } from '../../utils/formatters';
import type { HypothesisResponse } from '../../api/types';

interface HypothesesListProps {
  hypotheses: HypothesisResponse[];
  onSelectHypothesis?: (hypothesis: HypothesisResponse) => void;
}

export const HypothesesList: React.FC<HypothesesListProps> = ({ hypotheses }) => {
  const [expandedId, setExpandedId] = useState<string | null>(
    hypotheses.length > 0 ? hypotheses[0].id : null
  );

  const toggleExpand = (id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  if (!hypotheses || hypotheses.length === 0) {
    return (
      <div className="p-6 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
        No candidate hypotheses generated.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {hypotheses.map((h) => {
        const isExpanded = expandedId === h.id;
        const isTop = h.rank === 1;

        return (
          <div
            key={h.id}
            className={`rounded-xl border transition-all ${
              isTop
                ? 'border-amber-500/30 bg-dark-900/90 shadow-md'
                : 'border-dark-700 bg-dark-900/60'
            }`}
          >
            {/* Header Strip */}
            <div
              onClick={() => toggleExpand(h.id)}
              className="p-4 flex flex-wrap items-center justify-between gap-3 cursor-pointer select-none"
            >
              <div className="flex items-center gap-3">
                <span
                  className={`w-7 h-7 rounded-lg font-mono font-bold text-xs flex items-center justify-center border ${
                    isTop
                      ? 'bg-brand-amber text-dark-950 border-amber-400'
                      : 'bg-dark-800 text-dark-300 border-dark-700'
                  }`}
                >
                  #{h.rank}
                </span>

                <div>
                  <h3 className="text-sm font-semibold text-dark-100 flex items-center gap-2">
                    {h.summary}
                  </h3>
                  <div className="flex flex-wrap items-center gap-2 mt-1">
                    {h.matchedRules && h.matchedRules.length > 0 && (
                      <span className="text-[11px] font-mono text-dark-400 flex items-center gap-1">
                        <FileSearch className="w-3 h-3 text-dark-500" />
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
                    <span className="text-[10px] font-mono text-dark-400">Heuristic confidence score:</span>
                    <span
                      className={`font-mono text-sm font-bold ${
                        h.confidenceScore >= 0.7
                          ? 'text-emerald-400'
                          : h.confidenceScore >= 0.4
                          ? 'text-amber-400'
                          : 'text-dark-400'
                      }`}
                    >
                      {formatConfidence(h.confidenceScore)}
                    </span>
                  </div>
                  <div className="w-24 h-1.5 bg-dark-800 rounded-full overflow-hidden mt-1 ml-auto">
                    <div
                      className={`h-full rounded-full ${
                        h.confidenceScore >= 0.7
                          ? 'bg-emerald-400'
                          : h.confidenceScore >= 0.4
                          ? 'bg-brand-amber'
                          : 'bg-dark-600'
                      }`}
                      style={{ width: `${Math.min(100, Math.max(0, h.confidenceScore * 100))}%` }}
                    />
                  </div>
                </div>

                <button
                  className="p-1 rounded text-dark-400 hover:text-dark-100 transition-colors"
                  aria-label={isExpanded ? 'Collapse' : 'Expand'}
                >
                  {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {/* Expanded Content */}
            {isExpanded && (
              <div className="px-4 pb-4 pt-2 border-t border-dark-700/60 space-y-3">
                {/* Supporting & Counter Evidence */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
                  <div className="p-3 rounded-lg bg-dark-950 border border-dark-700/80">
                    <span className="text-dark-400 font-mono uppercase text-[11px] block mb-2 flex items-center gap-1">
                      <CheckCircle className="w-3.5 h-3.5 text-emerald-400" />
                      Supporting Evidence IDs ({h.evidenceIds?.length || 0})
                    </span>
                    {h.evidenceIds && h.evidenceIds.length > 0 ? (
                      <div className="flex flex-wrap gap-1.5">
                        {h.evidenceIds.map((eid) => (
                          <Badge key={eid} variant="green" mono>
                            {eid}
                          </Badge>
                        ))}
                      </div>
                    ) : (
                      <span className="text-dark-500 italic">No supporting evidence registered</span>
                    )}
                  </div>

                  <div className="p-3 rounded-lg bg-dark-950 border border-dark-700/80">
                    <span className="text-dark-400 font-mono uppercase text-[11px] block mb-2 flex items-center gap-1">
                      <XCircle className="w-3.5 h-3.5 text-rose-400" />
                      Contradicting Evidence IDs ({h.contradictingEvidenceIds?.length || 0})
                    </span>
                    {h.contradictingEvidenceIds && h.contradictingEvidenceIds.length > 0 ? (
                      <div className="flex flex-wrap gap-1.5">
                        {h.contradictingEvidenceIds.map((cid) => (
                          <Badge key={cid} variant="rose" mono>
                            {cid}
                          </Badge>
                        ))}
                      </div>
                    ) : (
                      <span className="text-dark-500 italic">None detected</span>
                    )}
                  </div>
                </div>

                {/* Verification Plan */}
                {h.verificationPlan && (
                  <div className="p-3 rounded-lg bg-dark-950 border border-dark-700/80 text-xs">
                    <span className="text-dark-400 font-mono uppercase text-[11px] block mb-1 flex items-center gap-1 font-semibold">
                      <ListChecks className="w-3.5 h-3.5 text-brand-amber" />
                      Verification Plan
                    </span>
                    <p className="text-dark-200 leading-relaxed font-sans">{h.verificationPlan}</p>
                  </div>
                )}

                {/* Counter Evidence details */}
                {h.counterEvidence && (
                  <div className="p-3 rounded-lg bg-rose-500/5 border border-rose-500/20 text-xs text-rose-300">
                    <span className="font-semibold block mb-0.5">Counter-Evidence Note:</span>
                    {h.counterEvidence}
                  </div>
                )}

                {/* Ranking Explanation */}
                {h.rankingExplanation && (
                  <div className="text-[11px] font-mono text-dark-400 bg-dark-950/60 p-2.5 rounded border border-dark-800">
                    <span className="text-dark-500">Ranking Explanation: </span>
                    {h.rankingExplanation}
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};
