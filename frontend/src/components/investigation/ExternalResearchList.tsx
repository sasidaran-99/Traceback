import React from 'react';
import { ExternalLink, Globe, Search } from 'lucide-react';
import { Badge } from '../shared/Badge';
import { formatDate } from '../../utils/formatters';
import type { ExternalResearchFinding } from '../../api/types';

interface ExternalResearchListProps {
  findings: ExternalResearchFinding[];
  sourceUrls?: string[];
}

export const ExternalResearchList: React.FC<ExternalResearchListProps> = ({
  findings,
}) => {
  if (!findings || findings.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
        <Globe className="w-6 h-6 mx-auto mb-2 text-dark-500" />
        <p className="font-medium text-dark-300">No external technical research findings recorded.</p>
        <p className="text-[11px] text-dark-500 mt-1">
          Tavily API may not be configured, or error signatures were completely resolved locally.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Disclaimer Banner */}
      <div className="p-3.5 rounded-xl border border-blue-500/20 bg-blue-500/5 text-xs text-blue-300 flex items-start gap-2.5">
        <Globe className="w-4 h-4 text-blue-400 shrink-0 mt-0.5" />
        <div className="leading-relaxed font-sans text-[11px]">
          <strong className="font-semibold text-blue-200">External Research Separation Invariant:</strong>{' '}
          These findings are retrieved live from technical documentation and issue trackers via the Tavily research engine. They explain general framework/library mechanics and failure patterns, but are strictly isolated from local Git/log repository evidence.
        </div>
      </div>

      {/* Findings Cards */}
      <div className="grid grid-cols-1 gap-3">
        {findings.map((f) => (
          <div
            key={f.findingId}
            className="p-4 rounded-xl border border-dark-700 bg-dark-900/80 hover:border-dark-600 transition-colors space-y-2 text-xs"
          >
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="flex items-center gap-2">
                <Badge variant="blue" mono size="sm">
                  {f.findingId}
                </Badge>
                <h4 className="font-medium text-dark-100 text-sm">{f.title}</h4>
              </div>

              <div className="flex items-center gap-2">
                <span className="text-[11px] font-mono text-dark-400">
                  Relevance: {(f.relevanceScore * 100).toFixed(0)}%
                </span>
                <span className="text-[11px] text-dark-500">
                  {formatDate(f.retrievedAt)}
                </span>
              </div>
            </div>

            {/* Query */}
            {f.query && (
              <div className="flex items-center gap-1.5 text-[11px] font-mono text-dark-400">
                <Search className="w-3 h-3 text-dark-500" />
                <span className="text-dark-500">Query:</span>
                <span className="text-dark-300 truncate">{f.query}</span>
              </div>
            )}

            {/* Snippet */}
            <p className="text-dark-200 font-sans leading-relaxed bg-dark-950/70 p-3 rounded-lg border border-dark-800 text-xs">
              {f.snippet}
            </p>

            {/* Source Link */}
            {f.url && (
              <div className="pt-1 flex items-center justify-between">
                <a
                  href={f.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1.5 text-xs font-mono text-brand-amber hover:text-amber-400 transition-colors underline-offset-2 hover:underline"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  <span className="truncate max-w-md">{f.url}</span>
                </a>
                <span className="text-[10px] font-mono uppercase text-dark-500">External Source</span>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};
