import React, { useState } from 'react';
import {
  Clock,
  GitCommit,
  AlertTriangle,
  FileCode,
  Layers,
} from 'lucide-react';
import { Badge } from '../shared/Badge';
import { formatDate, formatTimeOnly } from '../../utils/formatters';
import type { TimelineEvent } from '../../api/types';

interface ChronologicalTimelineProps {
  events: TimelineEvent[];
}

export const ChronologicalTimeline: React.FC<ChronologicalTimelineProps> = ({ events }) => {
  const [filterPreFailure, setFilterPreFailure] = useState<boolean | null>(null);

  if (!events || events.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
        No chronological events recorded on this investigation timeline.
      </div>
    );
  }

  // Sort chronologically ascending
  const sorted = [...events].sort(
    (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
  );

  const filtered = sorted.filter((e) => {
    if (filterPreFailure === null) return true;
    return e.isPreFailure === filterPreFailure;
  });

  const getEventIcon = (type: string) => {
    const norm = type.toUpperCase();
    if (norm.includes('COMMIT')) return <GitCommit className="w-4 h-4 text-brand-amber" />;
    if (norm.includes('ERROR') || norm.includes('EXCEPTION'))
      return <AlertTriangle className="w-4 h-4 text-rose-400" />;
    if (norm.includes('CONFIG')) return <FileCode className="w-4 h-4 text-purple-400" />;
    return <Layers className="w-4 h-4 text-blue-400" />;
  };

  return (
    <div className="space-y-4">
      {/* Timeline Controls */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded-xl border border-dark-700 bg-dark-900/80 text-xs">
        <div className="flex items-center gap-2">
          <Clock className="w-4 h-4 text-brand-amber" />
          <span className="font-mono font-semibold text-dark-200">
            Reconstructed Timeline ({events.length} Events)
          </span>
        </div>

        <div className="flex items-center gap-1.5 font-mono">
          <button
            onClick={() => setFilterPreFailure(null)}
            className={`px-2.5 py-1 rounded text-xs transition-colors ${
              filterPreFailure === null
                ? 'bg-dark-800 text-brand-amber border border-dark-700'
                : 'text-dark-400 hover:text-dark-200'
            }`}
          >
            All ({events.length})
          </button>
          <button
            onClick={() => setFilterPreFailure(true)}
            className={`px-2.5 py-1 rounded text-xs transition-colors ${
              filterPreFailure === true
                ? 'bg-dark-800 text-brand-amber border border-dark-700'
                : 'text-dark-400 hover:text-dark-200'
            }`}
          >
            Pre-Failure ({events.filter((e) => e.isPreFailure).length})
          </button>
          <button
            onClick={() => setFilterPreFailure(false)}
            className={`px-2.5 py-1 rounded text-xs transition-colors ${
              filterPreFailure === false
                ? 'bg-dark-800 text-brand-amber border border-dark-700'
                : 'text-dark-400 hover:text-dark-200'
            }`}
          >
            Failure / Post ({events.filter((e) => !e.isPreFailure).length})
          </button>
        </div>
      </div>

      {/* Timeline Track */}
      <div className="relative border-l border-dark-700 ml-4 space-y-4 py-2">
        {filtered.map((evt, idx) => {
          const isError = evt.eventType.toUpperCase().includes('ERROR');
          const isCommit = evt.eventType.toUpperCase().includes('COMMIT');

          return (
            <div key={evt.eventId || idx} className="relative pl-6">
              {/* Event node */}
              <div
                className={`absolute -left-2 top-1 w-4 h-4 rounded-full border-2 bg-dark-950 flex items-center justify-center ${
                  isError
                    ? 'border-rose-500 text-rose-500'
                    : isCommit
                    ? 'border-amber-400 text-amber-400'
                    : 'border-blue-400 text-blue-400'
                }`}
              />

              {/* Event Card */}
              <div
                className={`p-3.5 rounded-xl border text-xs transition-colors ${
                  isError
                    ? 'border-rose-500/30 bg-rose-500/5'
                    : isCommit
                    ? 'border-amber-500/20 bg-dark-900/90'
                    : 'border-dark-700 bg-dark-900/70'
                }`}
              >
                <div className="flex flex-wrap items-start justify-between gap-2 mb-1.5">
                  <div className="flex items-center gap-2">
                    <span className="p-1 rounded bg-dark-800 border border-dark-700">
                      {getEventIcon(evt.eventType)}
                    </span>
                    <span className="font-mono font-semibold text-dark-100 text-sm">
                      {evt.title}
                    </span>
                    <Badge variant={isError ? 'rose' : isCommit ? 'amber' : 'neutral'} mono size="sm">
                      {evt.eventType}
                    </Badge>
                    {evt.isPreFailure && (
                      <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-dark-800 text-emerald-400 border border-dark-700">
                        Pre-Failure
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-2 font-mono text-[11px] text-dark-400">
                    <span>{formatDate(evt.timestamp)}</span>
                    <span className="text-dark-600">({formatTimeOnly(evt.timestamp)})</span>
                  </div>
                </div>

                <p className="text-dark-300 font-sans leading-relaxed mb-2 font-mono text-xs">
                  {evt.description}
                </p>

                {/* Footer metadata & evidence link */}
                <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-dark-800/80 text-[11px] font-mono text-dark-400">
                  <div className="flex items-center gap-3">
                    {evt.entityId && (
                      <span>
                        <span className="text-dark-500">Entity:</span> {evt.entityId}
                      </span>
                    )}
                    {evt.source && (
                      <span>
                        <span className="text-dark-500">Source:</span> {evt.source}
                      </span>
                    )}
                  </div>

                  {evt.evidenceId && (
                    <Badge variant="blue" mono size="sm">
                      Evidence: {evt.evidenceId}
                    </Badge>
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
