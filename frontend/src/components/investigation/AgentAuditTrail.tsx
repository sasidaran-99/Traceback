import React from 'react';
import { Terminal, Clock } from 'lucide-react';
import { formatDate } from '../../utils/formatters';
import type { AgentInvestigationStep } from '../../api/types';

interface AgentAuditTrailProps {
  steps: AgentInvestigationStep[];
}

export const AgentAuditTrail: React.FC<AgentAuditTrailProps> = ({ steps }) => {
  if (!steps || steps.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-border-default bg-surface text-center text-text-muted text-xs">
        No agent execution steps recorded.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between pb-3 border-b border-border-subtle text-xs">
        <span className="font-mono uppercase tracking-wider text-text-primary font-bold flex items-center gap-2">
          <Terminal className="w-3.5 h-3.5 text-accent" />
          Autonomous Agent Execution Audit Log ({steps.length} Steps)
        </span>
        <span className="text-text-muted font-mono text-[11px]">Sequential Order</span>
      </div>

      <div className="relative border-l border-border-default ml-4 space-y-6 py-2">
        {steps.map((step) => {
          return (
            <div key={step.stepNumber} className="relative pl-6">
              {/* Step indicator node */}
              <div className="absolute -left-2.5 top-0.5 w-5 h-5 rounded-full bg-bg-app border-2 border-accent flex items-center justify-center text-[10px] font-mono font-bold text-accent tabular-nums">
                {step.stepNumber}
              </div>

              {/* Step content box */}
              <div className="p-4 rounded-xl border border-border-default bg-surface space-y-2.5 text-xs hover:border-border-default transition-colors">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <span className="font-mono font-bold text-text-primary text-sm">
                      {step.stepName}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5 text-text-muted font-mono text-[11px] tabular-nums">
                    <Clock className="w-3 h-3 text-text-muted" />
                    <span>{formatDate(step.timestamp)}</span>
                  </div>
                </div>

                <p className="text-text-secondary font-sans leading-relaxed">{step.description}</p>

                {/* Metadata Tags */}
                {step.metadata && Object.keys(step.metadata).length > 0 && (
                  <div className="pt-2 border-t border-border-subtle flex flex-wrap gap-2 text-[11px] font-mono">
                    {Object.entries(step.metadata).map(([k, v]) => (
                      <span
                        key={k}
                        className="px-2 py-0.5 rounded bg-bg-app border border-border-subtle text-text-muted"
                      >
                        <span className="text-text-muted">{k}:</span>{' '}
                        <span className="text-text-secondary">{String(v)}</span>
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
