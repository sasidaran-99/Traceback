import React, { useState } from 'react';
import {
  Clock,
  GitCommit,
  AlertTriangle,
  FileCode,
  Layers,
  FileWarning,
  ArrowUpDown,
  ExternalLink,
} from 'lucide-react';
import { Badge } from '../shared/Badge';
import { formatDate, formatTimeOnly } from '../../utils/formatters';
import type { TimelineEvent } from '../../api/types';

interface ChronologicalTimelineProps {
  events: TimelineEvent[];
  onSelectEvidence?: (evidenceId: string) => void;
  onInspectTimelineEvent?: (event: TimelineEvent) => void;
}

export const ChronologicalTimeline: React.FC<ChronologicalTimelineProps> = ({
  events,
  onSelectEvidence,
  onInspectTimelineEvent,
}) => {
  const [filterPreFailure, setFilterPreFailure] = useState<boolean | null>(null);
  // UX-054-16: Default newest-first
  const [sortOrder, setSortOrder] = useState<'desc' | 'asc'>('desc');

  if (!events || events.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-border-default bg-surface text-center text-text-muted text-xs">
        No chronological events recorded on this investigation timeline.
      </div>
    );
  }

  // Stable sort by timestamp (newest-first or oldest-first) using originalIndex to break ties
  const indexed = events.map((event, originalIndex) => ({ event, originalIndex }));
  const sorted = indexed
    .sort((a, b) => {
      const timeA = new Date(a.event.timestamp).getTime();
      const timeB = new Date(b.event.timestamp).getTime();
      if (timeA !== timeB) {
        return sortOrder === 'desc' ? timeB - timeA : timeA - timeB;
      }
      return sortOrder === 'desc'
        ? b.originalIndex - a.originalIndex
        : a.originalIndex - b.originalIndex;
    })
    .map((entry) => entry.event);

  const filtered = sorted.filter((e) => {
    if (filterPreFailure === null) return true;
    return e.isPreFailure === filterPreFailure;
  });

  const getEventIcon = (type: string) => {
    const norm = type.toUpperCase();
    if (norm.includes('WORKING_TREE') || norm.includes('ANOMALY'))
      return <FileWarning className="w-4 h-4 text-orange-400" />;
    if (norm.includes('COMMIT')) return <GitCommit className="w-4 h-4 text-accent" />;
    if (norm.includes('ERROR') || norm.includes('EXCEPTION') || norm.includes('STACK'))
      return <AlertTriangle className="w-4 h-4 text-rose-400" />;
    if (norm.includes('CONFIG')) return <FileCode className="w-4 h-4 text-[#7FA3C4]" />;
    return <Layers className="w-4 h-4 text-accent" />;
  };

  return (
    <div className="space-y-4">
      {/* Timeline Controls */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-border-default bg-surface text-xs shadow-sm">
        <div className="flex items-center gap-2">
          <Clock className="w-4 h-4 text-accent" />
          <span className="font-mono font-semibold text-text-primary">
            Reconstructed Timeline ({events.length} Events)
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {/* UX-054-16: Sort Order Controls */}
          <div className="flex items-center gap-1 font-mono bg-bg-app p-0.5 rounded-lg border border-border-default">
            <span className="text-[10px] uppercase text-text-muted px-2 flex items-center gap-1">
              <ArrowUpDown className="w-3 h-3 text-accent" />
              Sort:
            </span>
            <button
              type="button"
              onClick={() => setSortOrder('desc')}
              className={`px-2.5 py-1 rounded text-xs transition-colors cursor-pointer ${
                sortOrder === 'desc'
                  ? 'bg-bg-elevated text-accent border border-border-default font-semibold'
                  : 'text-text-muted hover:text-text-primary'
              }`}
              aria-pressed={sortOrder === 'desc'}
              title="Sort newest events first"
            >
              Newest First
            </button>
            <button
              type="button"
              onClick={() => setSortOrder('asc')}
              className={`px-2.5 py-1 rounded text-xs transition-colors cursor-pointer ${
                sortOrder === 'asc'
                  ? 'bg-bg-elevated text-accent border border-border-default font-semibold'
                  : 'text-text-muted hover:text-text-primary'
              }`}
              aria-pressed={sortOrder === 'asc'}
              title="Sort oldest events first"
            >
              Oldest First
            </button>
          </div>

          {/* Pre-Failure Filter Controls */}
          <div className="flex items-center gap-1.5 font-mono">
            <button
              onClick={() => setFilterPreFailure(null)}
              className={`px-3 py-1.5 rounded-md text-xs transition-colors ${
                filterPreFailure === null
                  ? 'bg-bg-elevated text-accent border border-border-default font-semibold'
                  : 'text-text-muted hover:text-text-primary'
              }`}
            >
              All ({events.length})
            </button>
            <button
              onClick={() => setFilterPreFailure(true)}
              className={`px-3 py-1.5 rounded-md text-xs transition-colors ${
                filterPreFailure === true
                  ? 'bg-bg-elevated text-accent border border-border-default font-semibold'
                  : 'text-text-muted hover:text-text-primary'
              }`}
            >
              Pre-Failure ({events.filter((e) => e.isPreFailure).length})
            </button>
            <button
              onClick={() => setFilterPreFailure(false)}
              className={`px-3 py-1.5 rounded-md text-xs transition-colors ${
                filterPreFailure === false
                  ? 'bg-bg-elevated text-accent border border-border-default font-semibold'
                  : 'text-text-muted hover:text-text-primary'
              }`}
            >
              Failure / Post ({events.filter((e) => !e.isPreFailure).length})
            </button>
          </div>
        </div>
      </div>

      {/* Timeline Track */}
      <div className="relative border-l border-border-default ml-4 space-y-4 py-2">
        {filtered.map((evt, idx) => {
          const isError =
            evt.eventType.includes('ERROR') ||
            evt.eventType.includes('EXCEPTION') ||
            evt.eventType.includes('ANOMALY');
          const isWorkingTree = evt.eventType.includes('WORKING_TREE');
          const isCommit = evt.eventType.includes('COMMIT');

          const eventTitle = evt.title || evt.summary;
          const eventDescription = evt.description || evt.detail;
          const evidenceRef = evt.relatedEvidenceId || evt.evidenceId;

          return (
            <div key={evt.eventId || idx} className="relative pl-6">
              {/* Node Icon on Timeline Line */}
              <div
                className={`absolute -left-3 top-1 w-6 h-6 rounded-full border-2 bg-bg-app flex items-center justify-center shadow-xs ${
                  isError
                    ? 'border-rose-400 text-rose-400'
                    : isCommit
                    ? 'border-accent text-accent'
                    : 'border-accent text-accent'
                }`}
              >
                {getEventIcon(evt.eventType)}
              </div>

              {/* Event Card */}
              <div
                onClick={() => onInspectTimelineEvent?.(evt)}
                className={`p-4 rounded-xl border text-xs space-y-2.5 transition-colors cursor-pointer ${
                  isError
                    ? 'border-rose-500/30 bg-rose-500/5 hover:border-rose-500/50'
                    : 'border-border-default bg-surface hover:border-border-strong'
                }`}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    {isWorkingTree && (
                      <Badge variant="orange" mono size="sm">
                        Filesystem Anomaly
                      </Badge>
                    )}
                    <Badge variant={isError ? 'rose' : isCommit ? 'amber' : 'neutral'} mono size="sm">
                      {evt.eventType}
                    </Badge>
                    {evt.isPreFailure && (
                      <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-bg-elevated text-emerald-400 border border-border-default">
                        Pre-Failure
                      </span>
                    )}
                    {evt.source && (
                      <span className="font-mono text-text-muted text-[11px]">{evt.source}</span>
                    )}
                  </div>

                  <div className="flex items-center gap-3 font-mono text-[11px] text-text-muted tabular-nums">
                    <div className="flex items-center gap-1.5">
                      <Clock className="w-3 h-3 text-text-muted" />
                      <span title={formatDate(evt.timestamp)}>
                        {formatTimeOnly(evt.timestamp)} ({formatDate(evt.timestamp)})
                      </span>
                    </div>

                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onInspectTimelineEvent?.(evt);
                      }}
                      className="p-1 rounded text-text-muted hover:text-accent transition-colors"
                      title="Inspect event details in Inspector"
                      aria-label="Inspect timeline event in Contextual Inspector"
                    >
                      <ExternalLink className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>

                {/* Event Title */}
                {eventTitle && (
                  <p className="font-sans text-xs text-text-primary leading-relaxed font-semibold">
                    {eventTitle}
                  </p>
                )}

                {/* Working Tree Metadata Counts */}
                {isWorkingTree && evt.metadata && (
                  <div className="flex flex-wrap items-center gap-2 text-xs font-mono text-text-secondary">
                    {evt.metadata.missingCount !== undefined && (
                      <span>Missing Tracked Files: {String(evt.metadata.missingCount)}</span>
                    )}
                    {evt.metadata.modifiedCount !== undefined && (
                      <span>| Modified: {String(evt.metadata.modifiedCount)}</span>
                    )}
                  </div>
                )}

                {/* Detail payload / context */}
                {eventDescription && eventDescription !== eventTitle && (
                  <div className="p-2.5 rounded-lg bg-bg-app border border-border-subtle font-mono text-xs text-text-secondary whitespace-pre-line leading-relaxed">
                    {eventDescription}
                  </div>
                )}

                {/* Associated Git commit or evidence link with clear visual grounding */}
                <div className="flex flex-wrap items-center gap-2 pt-1 border-t border-border-subtle">
                  {evt.isPreFailure && isCommit && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-[#C9A24A]/10 border border-[#C9A24A]/30 font-mono text-[11px] text-[#C9A24A] font-semibold">
                      Candidate Change
                    </span>
                  )}

                  {!evt.isPreFailure && isError && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/30 font-mono text-[11px] text-[#C86A6A] font-semibold">
                      Failure Impact
                    </span>
                  )}

                  {evt.associatedCommitSha && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-bg-app border border-border-default font-mono text-[11px] text-accent">
                      <GitCommit className="w-3 h-3 text-[#7FA3C4]" />
                      <span>commit: {evt.associatedCommitSha.substring(0, 7)}</span>
                    </span>
                  )}

                  {evidenceRef && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelectEvidence?.(evidenceRef);
                      }}
                      className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-bg-app border border-[#7FA3C4]/40 font-mono text-[11px] text-[#7FA3C4] hover:bg-[#1A2129] hover:border-[#7FA3C4] transition-colors cursor-pointer"
                      title={`Jump to evidence ${evidenceRef}`}
                    >
                      <span>Linked Evidence: {evidenceRef}</span>
                      <ExternalLink className="w-2.5 h-2.5 ml-0.5" />
                    </button>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
