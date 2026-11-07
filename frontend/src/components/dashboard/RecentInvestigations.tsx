import React from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, HelpCircle } from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { formatConfidence } from '../../utils/formatters';
import type { InvestigationAgentReport, InvestigationResponse } from '../../api/types';
import {
  AuthoritativeInvestigationItem,
  buildAuthoritativeInvestigations,
} from '../../utils/verdictAggregation';

interface RecentInvestigationsProps {
  investigations?: InvestigationResponse[];
  agentReports?: InvestigationAgentReport[];
  authoritativeItems?: AuthoritativeInvestigationItem[];
}

export const RecentInvestigations: React.FC<RecentInvestigationsProps> = ({
  investigations = [],
  agentReports = [],
  authoritativeItems,
}) => {
  const items: AuthoritativeInvestigationItem[] = React.useMemo(() => {
    if (authoritativeItems) return authoritativeItems;
    return buildAuthoritativeInvestigations(investigations, agentReports);
  }, [authoritativeItems, investigations, agentReports]);

  if (!items || items.length === 0) {
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
            Live records available in current server session (in-memory runtime)
          </p>
        </div>
        <Link
          to="/history"
          className="text-xs font-mono text-brand-amber hover:text-amber-400 flex items-center gap-1 transition-colors"
        >
          <span>View All ({items.length})</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </Link>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead className="bg-dark-950/70 border-b border-dark-700/80 text-dark-400 font-mono uppercase text-[10px] tracking-wider">
            <tr>
              <th className="py-3 px-4">Investigation ID</th>
              <th className="py-3 px-4">Service & Env</th>
              <th className="py-3 px-4">Verdict / Status</th>
              <th className="py-3 px-4">Analyzed</th>
              <th className="py-3 px-4">Top Root-Cause Hypothesis</th>
              <th className="py-3 px-4">
                <span
                  className="inline-flex items-center gap-1 cursor-help border-b border-dotted border-dark-500"
                  title="Heuristic confidence score based on deterministic scoring formula (S = T + O + A - C), not an empirical or statistical probability."
                >
                  <span>Heuristic Score</span>
                  <HelpCircle className="w-3 h-3 text-dark-500" />
                </span>
              </th>
              <th className="py-3 px-4 text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-dark-800 text-dark-200 font-mono">
            {items.slice(0, 5).map((inv) => (
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
                  <StatusPill status={inv.verdict} size="sm" />
                </td>
                <td className="py-3 px-4 text-dark-400 text-[11px]">
                  <span>{inv.commitsAnalyzed} commits</span>
                  <span className="text-dark-600 block">{inv.errorsDetected} errors</span>
                </td>
                <td className="py-3 px-4 max-w-xs truncate text-dark-300 font-sans text-xs">
                  {inv.topHypothesis?.summary || (inv.verdict === 'INSUFFICIENT_EVIDENCE' ? 'Inconclusive telemetry / Abstention' : 'Inconclusive / No Hypotheses')}
                </td>
                <td className="py-3 px-4">
                  {inv.topHypothesis ? (
                    <span
                      className={`font-bold ${
                        inv.topHypothesis.confidenceScore >= 0.7
                          ? 'text-emerald-400'
                          : inv.topHypothesis.confidenceScore >= 0.4
                          ? 'text-amber-400'
                          : 'text-dark-400'
                      }`}
                      title={`Heuristic confidence score: ${formatConfidence(inv.topHypothesis.confidenceScore)} (deterministic rank, not a statistical probability)`}
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
