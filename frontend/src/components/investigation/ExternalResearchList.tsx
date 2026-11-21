import React from 'react';
import { ExternalLink, Globe } from 'lucide-react';
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
      <div className="p-8 rounded-xl border border-border-default bg-surface text-center text-text-muted text-xs">
        <Globe className="w-6 h-6 mx-auto mb-2 text-text-muted" />
        <p className="font-medium text-text-secondary">No external technical research findings recorded.</p>
        <p className="text-[11px] text-text-muted mt-1 font-mono">
          Tavily Search API key unconfigured (deterministic offline mode active), or telemetry was completely resolved locally.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Disclaimer Banner */}
      <div className="p-4 rounded-xl border border-accent/30 bg-accent/5 text-xs text-text-secondary flex items-start gap-3">
        <Globe className="w-4 h-4 text-accent shrink-0 mt-0.5" />
        <div className="leading-relaxed font-sans text-xs">
          <strong className="font-semibold text-text-primary">External Research Separation Invariant:</strong>{' '}
          These findings are retrieved live from technical documentation and issue trackers via the Tavily research engine. They explain general framework/library mechanics and failure patterns, but are strictly isolated from local Git/log repository evidence.
        </div>
      </div>

      {/* Findings Cards */}
      <div className="grid grid-cols-1 gap-3">
        {findings.map((f) => (
          <div
            key={f.findingId}
            className="p-4 rounded-xl border border-border-default bg-surface hover:border-border-default transition-colors space-y-2.5 text-xs"
          >
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="flex items-center gap-2">
                <Badge variant="blue" mono size="sm">
                  {f.findingId}
                </Badge>
                <h4 className="font-semibold text-text-primary text-sm">{f.title}</h4>
              </div>

              <div className="flex items-center gap-3">
                <span className="text-[11px] font-mono text-accent tabular-nums">
                  Relevance: {((f.relevanceScore ?? 1) * 100).toFixed(0)}%
                </span>
                <span className="text-[11px] font-mono text-text-muted tabular-nums">
                  {formatDate(f.retrievedAt)}
                </span>
              </div>
            </div>

            {/* Query context */}
            <div className="text-[11px] font-mono text-text-muted">
              Query: <span className="text-text-secondary">&ldquo;{f.query}&rdquo;</span>
            </div>

            {/* Snippet */}
            <p className="font-sans text-xs text-text-secondary leading-relaxed p-3 rounded-lg bg-bg-app border border-border-subtle">
              {f.snippet}
            </p>

            {/* Source URL link */}
            {f.url && (
              <div className="pt-1 flex items-center justify-between text-xs">
                <a
                  href={f.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="font-mono text-xs text-accent hover:text-action-hover hover:underline inline-flex items-center gap-1.5 transition-colors"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  <span className="truncate max-w-lg">{f.url}</span>
                </a>
                {f.author && (
                  <span className="text-[11px] font-mono text-text-muted">Source: {f.author}</span>
                )}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};
