import React from 'react';
import { ShieldCheck, GitBranch, AlertTriangle, Activity } from 'lucide-react';
import type { InvestigationAgentReport, InvestigationResponse } from '../../api/types';
import {
  AuthoritativeInvestigationItem,
  VerdictMetrics,
  buildAuthoritativeInvestigations,
  calculateVerdictMetrics,
} from '../../utils/verdictAggregation';

interface StatsOverviewProps {
  investigations?: InvestigationResponse[];
  agentReports?: InvestigationAgentReport[];
  authoritativeItems?: AuthoritativeInvestigationItem[];
  metrics?: VerdictMetrics;
}

export const StatsOverview: React.FC<StatsOverviewProps> = ({
  investigations = [],
  agentReports = [],
  authoritativeItems,
  metrics,
}) => {
  const effectiveMetrics: VerdictMetrics = React.useMemo(() => {
    if (metrics) return metrics;
    const items =
      authoritativeItems || buildAuthoritativeInvestigations(investigations, agentReports);
    return calculateVerdictMetrics(items);
  }, [metrics, authoritativeItems, investigations, agentReports]);

  const total = effectiveMetrics.totalAnalyzed;

  const formatVerdictSubtitle = (m: VerdictMetrics): string => {
    let text = `${m.probableCount} Probable | ${m.insufficientCount} Insufficient`;
    if (m.pendingReportCount > 0) {
      text += ` | ${m.pendingReportCount} Pending`;
    }
    if (m.failedCount > 0) {
      text += ` | ${m.failedCount} Failed`;
    }
    return text;
  };

  const stats = [
    {
      label: 'Investigations Analyzed',
      value: total,
      sub:
        total > 0
          ? `${total} live session record${total === 1 ? '' : 's'} (in-memory runtime)`
          : 'No runs yet',
      icon: Activity,
      color: 'text-brand-amber',
    },
    {
      label: 'Hypotheses Evaluated',
      value: effectiveMetrics.totalHypotheses,
      sub: `${effectiveMetrics.totalCommits} commits examined`,
      icon: GitBranch,
      color: 'text-blue-400',
    },
    {
      label: 'Errors Diagnosed',
      value: effectiveMetrics.totalErrors,
      sub: 'Multi-line Java crash logs',
      icon: AlertTriangle,
      color: 'text-rose-400',
    },
    {
      label: 'Agent Root-Cause Verdicts',
      value: `${effectiveMetrics.confirmedCount} Confirmed`,
      sub: formatVerdictSubtitle(effectiveMetrics),
      icon: ShieldCheck,
      color: 'text-emerald-400',
    },
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {stats.map((stat, i) => {
        const Icon = stat.icon;
        return (
          <div
            key={i}
            className="p-4 rounded-xl border border-dark-700 bg-dark-900/80 hover:border-dark-600 transition-colors shadow-sm"
          >
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-mono uppercase text-dark-400 tracking-wider font-semibold">
                {stat.label}
              </span>
              <Icon className={`w-4 h-4 ${stat.color}`} />
            </div>
            <div className="text-xl font-bold font-mono text-dark-100">{stat.value}</div>
            <p className="text-[11px] font-mono text-dark-500 mt-1">{stat.sub}</p>
          </div>
        );
      })}
    </div>
  );
};
