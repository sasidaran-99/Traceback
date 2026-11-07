import React from 'react';
import { ShieldCheck, GitBranch, AlertTriangle, Activity } from 'lucide-react';
import type { InvestigationAgentReport, InvestigationResponse } from '../../api/types';

interface StatsOverviewProps {
  investigations: InvestigationResponse[];
  agentReports: InvestigationAgentReport[];
}

export const StatsOverview: React.FC<StatsOverviewProps> = ({
  investigations,
  agentReports,
}) => {
  const total = investigations.length;

  const totalHypotheses = investigations.reduce(
    (acc, inv) => acc + (inv.hypothesesCount || 0),
    0
  );

  const totalErrors = investigations.reduce(
    (acc, inv) => acc + (inv.errorsDetected || 0),
    0
  );

  const confirmedCount = agentReports.filter((r) => r.finalStatus === 'CONFIRMED').length;
  const probableCount = agentReports.filter((r) => r.finalStatus === 'PROBABLE').length;
  const insufficientCount = agentReports.filter(
    (r) => r.finalStatus === 'INSUFFICIENT_EVIDENCE'
  ).length;

  const stats = [
    {
      label: 'Investigations Analyzed',
      value: total,
      sub: total > 0 ? `${total} active in memory` : 'No runs yet',
      icon: Activity,
      color: 'text-brand-amber',
    },
    {
      label: 'Hypotheses Evaluated',
      value: totalHypotheses,
      sub: `${investigations.reduce((acc, i) => acc + i.commitsAnalyzed, 0)} commits examined`,
      icon: GitBranch,
      color: 'text-blue-400',
    },
    {
      label: 'Errors Diagnosed',
      value: totalErrors,
      sub: 'Multi-line Java crash logs',
      icon: AlertTriangle,
      color: 'text-rose-400',
    },
    {
      label: 'Agent Root-Cause Verdicts',
      value: `${confirmedCount} Confirmed`,
      sub: `${probableCount} Probable | ${insufficientCount} Insufficient`,
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
