import React from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { formatConfidence } from '../../utils/formatters';
import type { InvestigationResponse } from '../../api/types';

interface RecentInvestigationsProps {
  investigations: InvestigationResponse[];
}

export const RecentInvestigations: React.FC<RecentInvestigationsProps> = ({
  investigations,
}) => {
  if (!investigations || investigations.length === 0) {
    return null;
  }

  return (
    <div className="rounded-xl border border-dark-700 bg-dark-900/80 overflow-hidden shadow-sm">
      <div className="p-4 border-b border-dark-700/80 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-dark-100 font-mono">
            Recent Failure Investigations
          </h3>
          <p className="text-xs text-dark-400 mt-0.5">
            Real investigation records available in backend memory
          </p>
        </div>
        <Link
          to="/history"
          className="text-xs font-mono text-brand-amber hover:text-amber-400 flex items-center gap-1 transition-colors"
        >
          <span>View All ({investigations.length})</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </Link>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead className="bg-dark-950/70 border-b border-dark-700/80 text-dark-400 font-mono uppercase text-[10px] tracking-wider">
            <tr>
              <th className="py-3 px-4">Investigation ID</th>
              <th className="py-3 px-4">Service & Env</th>
              <th className="py-3 px-4">Status</th>
              <th className="py-3 px-4">Analyzed</th>
              <th className="py-3 px-4">Top Root-Cause Hypothesis</th>
              <th className="py-3 px-4">Confidence</th>
              <th className="py-3 px-4 text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-dark-800 text-dark-200 font-mono">
            {investigations.slice(0, 5).map((inv) => (
              <tr key={inv.investigationId} className="hover:bg-dark-850/50 transition-colors">
                <td className="py-3 px-4 font-bold text-dark-100">
                  <span className="truncate block max-w-[120px]">
                    {inv.investigationId.substring(0, 8)}...
                  </span>
                </td>
                <td className="py-3 px-4">
                  <span className="text-dark-100 font-semibold">{inv.serviceName}</span>
                  <span className="text-dark-500 block text-[11px] capitalize">
                    {inv.environment}
                  </span>
                </td>
                <td className="py-3 px-4">
                  <StatusPill status={inv.status} size="sm" />
                </td>
                <td className="py-3 px-4 text-dark-400 text-[11px]">
                  <span>{inv.commitsAnalyzed} commits</span>
                  <span className="text-dark-600 block">{inv.errorsDetected} errors</span>
                </td>
                <td className="py-3 px-4 max-w-xs truncate text-dark-300 font-sans text-xs">
                  {inv.topHypothesis?.summary || 'Inconclusive / No Hypotheses'}
                </td>
                <td className="py-3 px-4">
                  {inv.topHypothesis ? (
                    <span
                      className={`font-bold ${
                        inv.topHypothesis.confidenceScore >= 0.7
                          ? 'text-emerald-400'
                          : 'text-amber-400'
                      }`}
                    >
                      {formatConfidence(inv.topHypothesis.confidenceScore)}
                    </span>
                  ) : (
                    <span className="text-dark-500">N/A</span>
                  )}
                </td>
                <td className="py-3 px-4 text-right">
                  <Link
                    to={`/investigations/${inv.investigationId}`}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-dark-800 text-brand-amber hover:bg-dark-700 transition-colors border border-dark-700 text-xs"
                  >
                    <span>Inspect</span>
                    <ArrowRight className="w-3 h-3" />
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};
