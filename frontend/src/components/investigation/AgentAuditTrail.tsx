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
      <div className="p-6 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
        No agent execution steps recorded.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between pb-2 border-b border-dark-700/80 text-xs">
        <span className="font-mono uppercase tracking-wider text-dark-400 font-semibold flex items-center gap-2">
          <Terminal className="w-3.5 h-3.5 text-brand-amber" />
          Autonomous Agent Execution Audit Log ({steps.length} Steps)
        </span>
        <span className="text-dark-500 font-mono text-[11px]">Sequential Order</span>
      </div>

      <div className="relative border-l border-dark-700 ml-4 space-y-6 py-2">
        {steps.map((step) => {
          return (
            <div key={step.stepNumber} className="relative pl-6">
              {/* Step indicator node */}
              <div className="absolute -left-2.5 top-0.5 w-5 h-5 rounded-full bg-dark-800 border-2 border-brand-amber flex items-center justify-center text-[10px] font-mono font-bold text-brand-amber">
                {step.stepNumber}
              </div>

              {/* Step content box */}
              <div className="p-3.5 rounded-lg border border-dark-700 bg-dark-900/80 space-y-2 text-xs">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <span className="font-mono font-bold text-dark-100 text-sm">
                      {step.stepName}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5 text-dark-500 font-mono text-[11px]">
                    <Clock className="w-3 h-3" />
                    <span>{formatDate(step.timestamp)}</span>
                  </div>
                </div>

                <p className="text-dark-300 font-sans leading-relaxed">{step.description}</p>

                {/* Metadata Tags */}
                {step.metadata && Object.keys(step.metadata).length > 0 && (
                  <div className="pt-2 border-t border-dark-800/80 flex flex-wrap gap-2 text-[11px] font-mono">
                    {Object.entries(step.metadata).map(([k, v]) => (
                      <span
                        key={k}
                        className="px-2 py-0.5 rounded bg-dark-950 border border-dark-700/60 text-dark-400"
                      >
                        <span className="text-dark-500">{k}:</span>{' '}
                        <span className="text-dark-200">
                          {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                        </span>
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
