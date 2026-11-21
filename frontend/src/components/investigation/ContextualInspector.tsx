import React, { useState } from 'react';
import {
  CheckCircle2,
  AlertTriangle,
  HelpCircle,
  ShieldCheck,
  ShieldAlert,
  Copy,
  Check,
  X,
  FileText,
  Hash,
  Info,
} from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { Badge } from '../shared/Badge';
import { formatDate, formatConfidence } from '../../utils/formatters';
import type {
  EvidenceItem,
  HypothesisResponse,
  InvestigationClaim,
  TimelineEvent,
} from '../../api/types';

export type InspectorSelection =
  | { type: 'evidence'; item: EvidenceItem }
  | { type: 'claim'; item: InvestigationClaim }
  | { type: 'hypothesis'; item: HypothesisResponse }
  | { type: 'timeline'; item: TimelineEvent }
  | null;

export interface ContextualInspectorProps {
  selection: InspectorSelection;
  verdict: string;
  candidateScore?: number;
  /** @deprecated Retained for backward compatibility */
  investigationScore?: number;
  heuristicScore?: number;
  supportingCount: number;
  contradictoryCount: number;
  onSelectEvidence?: (evidenceId: string) => void;
  onClearSelection: () => void;
}

export const ContextualInspector: React.FC<ContextualInspectorProps> = ({
  selection,
  verdict,
  candidateScore,
  investigationScore,
  heuristicScore,
  supportingCount,
  contradictoryCount,
  onSelectEvidence,
  onClearSelection,
}) => {
  const [copiedText, setCopiedText] = useState<string | null>(null);
  const scoreValue =
    candidateScore !== undefined
      ? candidateScore
      : heuristicScore !== undefined
      ? heuristicScore
      : investigationScore;

  const handleCopy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedText(label);
      setTimeout(() => setCopiedText(null), 2000);
    } catch {
      // ignore clipboard error
    }
  };

  const getVerdictIcon = (st: string) => {
    const norm = st.toUpperCase();
    if (norm === 'CONFIRMED') return <CheckCircle2 className="w-4 h-4 text-[#4FA88A]" />;
    if (norm === 'PROBABLE') return <AlertTriangle className="w-4 h-4 text-[#C9A24A]" />;
    return <HelpCircle className="w-4 h-4 text-[#6B7585]" />;
  };

  // If no object is selected, render the Default Grounding Verdict & Evidence Accounting Profile
  if (!selection) {
    return (
      <div className="space-y-4 font-sans select-none">
        {/* Grounding Verdict Card */}
        <div className="p-4 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-3.5 shadow-xs">
          <div className="flex items-center justify-between pb-2.5 border-b border-[#1F262E]">
            <div className="flex items-center gap-2">
              {getVerdictIcon(verdict)}
              <span className="text-xs font-mono font-bold uppercase tracking-wider text-[#E4E8EE]">
                Grounding Verdict: {verdict}
              </span>
            </div>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#1A2129] text-[#6B7585] border border-[#1F262E]">
              OVERVIEW
            </span>
          </div>

          <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
            {verdict === 'CONFIRMED'
              ? 'Strong deterministic evidence links the suspect change or anomaly directly to the observed incident with zero unrefuted counter-evidence.'
              : verdict === 'PROBABLE'
              ? 'Plausible contributing change identified with strong structural overlap. The evidence supports investigating this change, but does not independently establish deployment or causation.'
              : 'Available telemetry is insufficient or inconclusive to establish causal proof. Honest abstention enforced.'}
          </p>

          <div className="pt-2 border-t border-[#1F262E] space-y-2 text-xs font-mono">
            {scoreValue !== undefined ? (
              <div className="flex items-center justify-between p-2 rounded bg-[#0C1014] border border-[#1F262E]">
                <div className="flex flex-col">
                  <span className="text-xs text-[#7FA3C4] font-semibold">Heuristic Candidate Score</span>
                  <span className="text-[10px] text-[#6B7585]">Formula: S = T + O + A - C (Prioritization only)</span>
                </div>
                <span
                  className="text-xs text-[#7FA3C4] font-bold tabular-nums"
                  title="Deterministic ranking score based on heuristic formula (S = T + O + A - C, not a statistical probability)"
                >
                  {(scoreValue * 100).toFixed(0)}% ({scoreValue.toFixed(2)})
                </span>
              </div>
            ) : (
              <div className="flex items-center justify-between p-2 rounded bg-[#0C1014] border border-[#1F262E]">
                <div className="flex flex-col">
                  <span className="text-xs text-[#A7B0BC] font-semibold">Heuristic Candidate Score</span>
                  <span className="text-[10px] text-[#6B7585]">Formula: S = T + O + A - C (Prioritization only)</span>
                </div>
                <span
                  className="text-xs text-[#6B7585] italic font-mono"
                  title={verdict === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry is insufficient to score candidate correlation' : 'No deterministic candidate score recorded'}
                >
                  {verdict === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry Insufficient' : 'Unscored'}
                </span>
              </div>
            )}

            <div className="flex items-center justify-between text-[11px] pt-1">
              <span className="text-[#6B7585]">Telemetry Basis:</span>
              <span className="text-[#A7B0BC]">Git &amp; Working Tree</span>
            </div>
          </div>
        </div>

        {/* Evidence Registry Profile */}
        <div className="p-4 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-3 font-mono text-xs shadow-xs">
          <span className="text-[11px] uppercase tracking-wider text-[#6B7585] font-semibold block">
            Evidence Accounting
          </span>

          <div className="space-y-2">
            <div className="flex items-center justify-between p-2 rounded bg-[#0C1014] border border-[#1F262E]">
              <span className="text-[#4FA88A] flex items-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5" />
                Supporting Evidence:
              </span>
              <strong className="text-[#E4E8EE] tabular-nums">{supportingCount} items</strong>
            </div>

            <div className="flex items-center justify-between p-2 rounded bg-[#0C1014] border border-[#1F262E]">
              <span className="text-[#6B7585] flex items-center gap-1.5">
                <ShieldAlert className="w-3.5 h-3.5 text-[#6B7585]" />
                Contradictory Evidence:
              </span>
              <strong className="text-[#A7B0BC] tabular-nums">{contradictoryCount} items</strong>
            </div>
          </div>

          <div className="pt-2 border-t border-[#1F262E] text-[11px] text-[#6B7585] space-y-1">
            <div className="flex items-center justify-between">
              <span>Registry Audit:</span>
              <span className="text-[#4FA88A]">SHA-256 Verified</span>
            </div>
            <div className="flex items-center justify-between">
              <span>Integrity Store:</span>
              <span className="text-[#A7B0BC]">PostgreSQL</span>
            </div>
          </div>
        </div>

        {/* Contextual Guidance Pill */}
        <div className="p-3 rounded-lg border border-[#1F262E] bg-[#0C1014] text-[11px] font-mono text-[#6B7585] flex items-start gap-2">
          <Info className="w-3.5 h-3.5 text-[#7FA3C4] shrink-0 mt-0.5" />
          <p className="leading-relaxed">
            Click any hypothesis, claim, evidence card, or timeline event to inspect its forensic provenance in this panel.
          </p>
        </div>
      </div>
    );
  }

  // Active Contextual Inspector View for Selected Object
  const { type, item } = selection;

  return (
    <div className="space-y-3 font-sans select-none">
      {/* Inspector Header with Dismiss Action */}
      <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-3 shadow-xs">
        <div className="flex items-center justify-between pb-2 border-b border-[#1F262E]">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-[#7FA3C4]" />
            <span className="text-xs font-mono font-bold uppercase tracking-wider text-[#E4E8EE]">
              {type === 'evidence' && 'Evidence Inspector'}
              {type === 'claim' && 'Claim Inspector'}
              {type === 'hypothesis' && 'Hypothesis Inspector'}
              {type === 'timeline' && 'Timeline Event Inspector'}
            </span>
          </div>
          <button
            type="button"
            onClick={onClearSelection}
            className="inline-flex items-center gap-1 text-[11px] font-mono text-[#6B7585] hover:text-[#E4E8EE] p-1 rounded hover:bg-[#1A2129] transition-colors"
            title="Close inspector and restore overview"
            aria-label="Close inspector and restore overview"
          >
            <X className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Overview</span>
          </button>
        </div>

        {/* ================= EVIDENCE INSPECTION ================= */}
        {type === 'evidence' && (() => {
          const ev = item as EvidenceItem;
          return (
            <div className="space-y-3 text-xs">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-1.5 font-mono">
                  <span className="text-[#6B7585]">ID:</span>
                  <span className="text-[#E4E8EE] font-bold">{ev.evidenceId}</span>
                  <button
                    type="button"
                    onClick={() => handleCopy(ev.evidenceId, 'id')}
                    className="text-[#6B7585] hover:text-[#E4E8EE] p-0.5 rounded transition-colors"
                    title="Copy Evidence ID"
                  >
                    {copiedText === 'id' ? <Check className="w-3 h-3 text-[#4FA88A]" /> : <Copy className="w-3 h-3" />}
                  </button>
                </div>
                <Badge variant="blue" mono size="sm">
                  {ev.evidenceType}
                </Badge>
              </div>

              <div className="p-2.5 rounded bg-[#0C1014] border border-[#1F262E] font-sans text-xs text-[#E4E8EE] leading-relaxed">
                {ev.summary}
              </div>

              <div className="space-y-1.5 font-mono text-[11px] border-t border-[#1F262E] pt-2">
                <div className="flex justify-between text-[#6B7585]">
                  <span>Source:</span>
                  <span className="text-[#A7B0BC]">{ev.source}</span>
                </div>
                {ev.timestamp && (
                  <div className="flex justify-between text-[#6B7585]">
                    <span>Timestamp:</span>
                    <span className="text-[#A7B0BC]">{formatDate(ev.timestamp)}</span>
                  </div>
                )}
                {ev.integrityHash && (
                  <div className="space-y-0.5 pt-1">
                    <span className="text-[#6B7585] flex items-center gap-1">
                      <Hash className="w-3 h-3" />
                      SHA-256 Checksum:
                    </span>
                    <div className="p-1.5 rounded bg-[#0C1014] border border-[#1F262E] text-[10px] text-[#7FA3C4] break-all font-mono">
                      {ev.integrityHash}
                    </div>
                  </div>
                )}
              </div>

              {/* Payload Container if present */}
              {ev.originalReference && (
                <div className="space-y-1 pt-1 border-t border-[#1F262E]">
                  <div className="flex items-center justify-between text-[11px] font-mono text-[#6B7585]">
                    <span>Original Reference:</span>
                    <button
                      type="button"
                      onClick={() => handleCopy(ev.originalReference || '', 'payload')}
                      className="text-[10px] text-[#7FA3C4] hover:underline"
                    >
                      {copiedText === 'payload' ? 'Copied' : 'Copy'}
                    </button>
                  </div>
                  <div className="max-h-48 overflow-y-auto rounded bg-[#0C1014] p-2 border border-[#1F262E]">
                    <pre className="text-[11px] font-mono text-[#E4E8EE] whitespace-pre-wrap break-all leading-snug">
                      {ev.originalReference}
                    </pre>
                  </div>
                </div>
              )}

              {/* Artifact Metadata if present */}
              {ev.metadata && Object.keys(ev.metadata).length > 0 && (
                <div className="space-y-1.5 pt-1 border-t border-[#1F262E] text-[11px] font-mono">
                  <span className="text-[#6B7585] font-semibold uppercase text-[10px] block">Metadata</span>
                  <div className="p-2 rounded bg-[#0C1014] border border-[#1F262E] space-y-1">
                    {Object.entries(ev.metadata).map(([k, v]) => (
                      <div key={k} className="flex justify-between gap-2">
                        <span className="text-[#6B7585]">{k}:</span>
                        <span className="text-[#A7B0BC] truncate">{typeof v === 'object' ? JSON.stringify(v) : String(v)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          );
        })()}

        {/* ================= CLAIM INSPECTION ================= */}
        {type === 'claim' && (() => {
          const claim = item as InvestigationClaim;
          return (
            <div className="space-y-3 text-xs font-mono">
              <div className="flex items-center justify-between gap-2">
                <span className="text-[#6B7585] text-[11px] font-bold">{claim.claimId}</span>
                <StatusPill status={claim.status} size="sm" />
              </div>

              <div className="p-2.5 rounded bg-[#0C1014] border border-[#1F262E] font-sans text-xs text-[#E4E8EE] leading-relaxed">
                "{claim.statement}"
              </div>

              {claim.explanation && (
                <div className="p-2 rounded bg-[#080B0E] border border-[#1F262E] text-[11px] text-[#A7B0BC] leading-relaxed">
                  <span className="text-[#6B7585] font-bold block mb-1">Validation Rationale:</span>
                  {claim.explanation}
                </div>
              )}

              {/* Supporting Evidence IDs */}
              <div className="space-y-1.5 pt-1 border-t border-[#1F262E]">
                <span className="text-[#4FA88A] text-[11px] font-semibold flex items-center gap-1">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  Supporting Evidence ({claim.supportingEvidenceIds?.length || 0}):
                </span>
                {claim.supportingEvidenceIds && claim.supportingEvidenceIds.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5">
                    {claim.supportingEvidenceIds.map((evId) => (
                      <button
                        key={evId}
                        type="button"
                        onClick={() => onSelectEvidence?.(evId)}
                        className="px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C] text-[11px] text-[#4FA88A] hover:text-[#E4E8EE] hover:border-[#4FA88A]/60 transition-colors cursor-pointer"
                        title={`Inspect evidence ${evId}`}
                      >
                        {evId}
                      </button>
                    ))}
                  </div>
                ) : (
                  <span className="text-[#6B7585] italic text-[11px]">None cited</span>
                )}
              </div>

              {/* Contradictory Evidence IDs */}
              <div className="space-y-1.5 pt-1 border-t border-[#1F262E]">
                <span className="text-[#C86A6A] text-[11px] font-semibold flex items-center gap-1">
                  <ShieldAlert className="w-3.5 h-3.5" />
                  Counter Evidence ({claim.contradictingEvidenceIds?.length || 0}):
                </span>
                {claim.contradictingEvidenceIds && claim.contradictingEvidenceIds.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5">
                    {claim.contradictingEvidenceIds.map((evId) => (
                      <button
                        key={evId}
                        type="button"
                        onClick={() => onSelectEvidence?.(evId)}
                        className="px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C] text-[11px] text-[#C86A6A] hover:text-[#E4E8EE] hover:border-[#C86A6A]/60 transition-colors cursor-pointer"
                        title={`Inspect counter-evidence ${evId}`}
                      >
                        {evId}
                      </button>
                    ))}
                  </div>
                ) : (
                  <span className="text-[#6B7585] italic text-[11px]">None observed</span>
                )}
              </div>
            </div>
          );
        })()}

        {/* ================= HYPOTHESIS INSPECTION ================= */}
        {type === 'hypothesis' && (() => {
          const hyp = item as HypothesisResponse;
          const bd = hyp.confidenceBreakdown || hyp.breakdown;
          return (
            <div className="space-y-3 text-xs font-mono">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-bold text-[#7FA3C4] bg-[#1A2129] px-2 py-0.5 rounded border border-[#2A323C]">
                  Candidate #{hyp.rank}
                </span>
                <StatusPill status={hyp.status || 'PROPOSED'} size="sm" />
              </div>

              <div className="p-2.5 rounded bg-[#0C1014] border border-[#1F262E] font-sans text-xs text-[#E4E8EE] leading-relaxed">
                {hyp.summary}
              </div>

              {hyp.description && (
                <p className="text-[11px] text-[#A7B0BC] font-sans leading-relaxed">
                  {hyp.description}
                </p>
              )}

              {/* Heuristic Score Callout */}
              <div className="p-2.5 rounded bg-[#0C1014] border border-[#1F262E] space-y-1">
                <div className="flex items-center justify-between">
                  <span className="text-[#6B7585]">Heuristic Score:</span>
                  <span className="text-sm font-bold text-[#7FA3C4] tabular-nums">
                    {formatConfidence(hyp.confidenceScore)}
                  </span>
                </div>
                <span className="text-[10px] text-[#6B7585] block">
                  Relative candidate prioritization metric (S = T + O + A - C)
                </span>
              </div>

              {/* Factor Breakdown if available */}
              <div className="space-y-1.5 pt-1 border-t border-[#1F262E] text-[11px]">
                <span className="text-[#6B7585] font-semibold uppercase text-[10px] block">
                  Deterministic Factors
                </span>
                {bd ? (
                  <div className="grid grid-cols-2 gap-1.5">
                    <div className="p-1.5 rounded bg-[#0C1014] border border-[#1F262E] flex justify-between">
                      <span className="text-[#6B7585]">T (Time):</span>
                      <span className="text-[#E4E8EE] font-bold">+{bd.temporalPoints}</span>
                    </div>
                    <div className="p-1.5 rounded bg-[#0C1014] border border-[#1F262E] flex justify-between">
                      <span className="text-[#6B7585]">O (Overlap):</span>
                      <span className="text-[#E4E8EE] font-bold">+{bd.overlapPoints}</span>
                    </div>
                    <div className="p-1.5 rounded bg-[#0C1014] border border-[#1F262E] flex justify-between">
                      <span className="text-[#6B7585]">A (Agree):</span>
                      <span className="text-[#E4E8EE] font-bold">+{bd.agreementPoints}</span>
                    </div>
                    <div className="p-1.5 rounded bg-[#0C1014] border border-[#1F262E] flex justify-between">
                      <span className="text-[#6B7585]">C (Penalty):</span>
                      <span className="text-[#C86A6A] font-bold">-{bd.contradictionPenalty}</span>
                    </div>
                  </div>
                ) : (
                  <span className="text-[#6B7585] italic">Factor breakdown unavailable for candidate</span>
                )}
              </div>

              {/* Supporting Evidence IDs */}
              <div className="space-y-1.5 pt-1 border-t border-[#1F262E]">
                <span className="text-[#4FA88A] text-[11px] font-semibold flex items-center gap-1">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  Supporting Evidence ({hyp.supportingEvidenceIds?.length || 0}):
                </span>
                {hyp.supportingEvidenceIds && hyp.supportingEvidenceIds.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5">
                    {hyp.supportingEvidenceIds.map((evId) => (
                      <button
                        key={evId}
                        type="button"
                        onClick={() => onSelectEvidence?.(evId)}
                        className="px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C] text-[11px] text-[#4FA88A] hover:text-[#E4E8EE] hover:border-[#4FA88A]/60 transition-colors cursor-pointer"
                        title={`Inspect evidence ${evId}`}
                      >
                        {evId}
                      </button>
                    ))}
                  </div>
                ) : (
                  <span className="text-[#6B7585] italic text-[11px]">No specific evidence IDs linked</span>
                )}
              </div>

              {/* Counter Evidence */}
              {hyp.counterEvidence && (
                <div className="p-2 rounded bg-[#0C1014] border border-[#1F262E] text-[11px] text-[#C86A6A] leading-relaxed">
                  <span className="font-semibold block mb-0.5">Counter Evidence:</span>
                  {hyp.counterEvidence}
                </div>
              )}
            </div>
          );
        })()}

        {/* ================= TIMELINE EVENT INSPECTION ================= */}
        {type === 'timeline' && (() => {
          const evt = item as TimelineEvent;
          const evRef = evt.relatedEvidenceId || evt.evidenceId;
          return (
            <div className="space-y-3 text-xs font-mono">
              <div className="flex items-center justify-between gap-2">
                <Badge variant={evt.isPreFailure ? 'amber' : 'rose'} mono size="sm">
                  {evt.eventType}
                </Badge>
                {evt.isPreFailure && (
                  <span className="text-[10px] px-1.5 py-0.2 rounded bg-[#1A2129] text-emerald-400 border border-[#2A323C]">
                    Pre-Failure
                  </span>
                )}
              </div>

              <div className="p-2.5 rounded bg-[#0C1014] border border-[#1F262E] font-sans text-xs text-[#E4E8EE] leading-relaxed font-semibold">
                {evt.title || evt.summary}
              </div>

              {evt.description && evt.description !== (evt.title || evt.summary) && (
                <p className="text-[11px] text-[#A7B0BC] font-mono leading-relaxed p-2 rounded bg-[#080B0E] border border-[#1F262E]">
                  {evt.description}
                </p>
              )}

              <div className="space-y-1.5 pt-1 border-t border-[#1F262E] text-[11px]">
                <div className="flex justify-between text-[#6B7585]">
                  <span>Timestamp:</span>
                  <span className="text-[#A7B0BC]">{formatDate(evt.timestamp)}</span>
                </div>
                {evt.source && (
                  <div className="flex justify-between text-[#6B7585]">
                    <span>Source:</span>
                    <span className="text-[#A7B0BC]">{evt.source}</span>
                  </div>
                )}
                {evt.associatedCommitSha && (
                  <div className="flex justify-between text-[#6B7585]">
                    <span>Commit SHA:</span>
                    <span className="text-[#7FA3C4]">{evt.associatedCommitSha.substring(0, 7)}</span>
                  </div>
                )}
              </div>

              {/* Linked Evidence reference */}
              {evRef && (
                <div className="pt-2 border-t border-[#1F262E]">
                  <span className="text-[#6B7585] text-[11px] block mb-1.5">Linked Evidence Item:</span>
                  <button
                    type="button"
                    onClick={() => onSelectEvidence?.(evRef)}
                    className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded bg-[#1A2129] border border-[#2A323C] text-[#7FA3C4] hover:text-[#E4E8EE] hover:border-[#7FA3C4] transition-colors cursor-pointer text-xs"
                    title={`Inspect linked evidence ${evRef}`}
                  >
                    <FileText className="w-3.5 h-3.5" />
                    <span>Evidence: {evRef}</span>
                  </button>
                </div>
              )}
            </div>
          );
        })()}
      </div>
    </div>
  );
};
