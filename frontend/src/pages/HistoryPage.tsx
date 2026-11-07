import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { History, ArrowRight, Search, RefreshCw, AlertCircle } from 'lucide-react';
import { StatusPill } from '../components/shared/StatusPill';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { formatDate, formatConfidence } from '../utils/formatters';
import { investigationApi } from '../api/investigationApi';
import type { InvestigationResponse } from '../api/types';

export const HistoryPage: React.FC = () => {
  const [investigations, setInvestigations] = useState<InvestigationResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  const fetchHistory = async () => {
    setIsLoading(true);
    try {
      const data = await investigationApi.getAllInvestigations();
      setInvestigations(data);
      setError(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to retrieve investigation history');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchHistory();
  }, []);

  const filtered = investigations.filter((inv) => {
    const matchesSearch =
      inv.investigationId.toLowerCase().includes(searchTerm.toLowerCase()) ||
      inv.serviceName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (inv.topHypothesis?.summary || '').toLowerCase().includes(searchTerm.toLowerCase());

    const matchesStatus = statusFilter === 'ALL' || inv.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-dark-700/80">
        <div>
          <h2 className="text-lg font-mono font-bold text-dark-100 flex items-center gap-2">
            <History className="w-5 h-5 text-brand-amber" />
            Investigation History
          </h2>
          <p className="text-xs text-dark-400 mt-1">
            Complete record of autonomous failure analyses held in backend memory.
          </p>
        </div>

        <button
          onClick={fetchHistory}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-dark-700 bg-dark-800 text-xs font-mono text-dark-300 hover:text-dark-100 transition-colors"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
          <span>Refresh</span>
        </button>
      </div>

      {/* Filter and Search Bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded-xl border border-dark-700 bg-dark-900/80">
        <div className="relative flex-1 min-w-[240px]">
          <Search className="w-4 h-4 text-dark-500 absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search by ID, service name, or summary keyword..."
            className="w-full pl-9 pr-3.5 py-1.5 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber"
          />
        </div>

        <div className="flex items-center gap-1.5 font-mono text-xs">
          {['ALL', 'COMPLETED', 'INSUFFICIENT_EVIDENCE'].map((st) => (
            <button
              key={st}
              onClick={() => setStatusFilter(st)}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                statusFilter === st
                  ? 'bg-dark-800 text-brand-amber border border-dark-700 font-semibold'
                  : 'text-dark-400 hover:text-dark-200'
              }`}
            >
              {st}
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      {isLoading ? (
        <LoadingSpinner label="Fetching investigations from backend..." />
      ) : error ? (
        <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-500/10 text-xs text-rose-300 flex items-center gap-2">
          <AlertCircle className="w-4 h-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="p-12 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
          No investigations match your current filter.
        </div>
      ) : (
        <div className="rounded-xl border border-dark-700 bg-dark-900/80 overflow-hidden shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="bg-dark-950/70 border-b border-dark-700/80 text-dark-400 font-mono uppercase text-[10px] tracking-wider">
                <tr>
                  <th className="py-3 px-4">Investigation ID</th>
                  <th className="py-3 px-4">Service</th>
                  <th className="py-3 px-4">Environment</th>
                  <th className="py-3 px-4">Status</th>
                  <th className="py-3 px-4">Commits / Errors</th>
                  <th className="py-3 px-4">Leading Root-Cause Hypothesis</th>
                  <th className="py-3 px-4">Score</th>
                  <th className="py-3 px-4">Date</th>
                  <th className="py-3 px-4 text-right">Dossier</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-dark-800 text-dark-200 font-mono">
                {filtered.map((inv) => (
                  <tr key={inv.investigationId} className="hover:bg-dark-850/50 transition-colors">
                    <td className="py-3 px-4 font-bold text-dark-100">
                      <span className="truncate block max-w-[130px]">
                        {inv.investigationId.substring(0, 8)}...
                      </span>
                    </td>
                    <td className="py-3 px-4 font-semibold text-dark-100">
                      {inv.serviceName}
                    </td>
                    <td className="py-3 px-4 capitalize text-dark-300">
                      {inv.environment}
                    </td>
                    <td className="py-3 px-4">
                      <StatusPill status={inv.status} size="sm" />
                    </td>
                    <td className="py-3 px-4 text-dark-400 text-[11px]">
                      <span>{inv.commitsAnalyzed} commits</span>
                      <span className="text-dark-600 block">{inv.errorsDetected} errors</span>
                    </td>
                    <td className="py-3 px-4 max-w-sm truncate text-dark-300 font-sans text-xs">
                      {inv.topHypothesis?.summary || 'Inconclusive telemetry'}
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
                    <td className="py-3 px-4 text-dark-500 text-[11px] whitespace-nowrap">
                      {formatDate(inv.completedAt || inv.startedAt)}
                    </td>
                    <td className="py-3 px-4 text-right">
                      <Link
                        to={`/investigations/${inv.investigationId}`}
                        className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-dark-800 text-brand-amber hover:bg-dark-750 transition-colors border border-dark-700 text-xs"
                      >
                        <span>Open</span>
                        <ArrowRight className="w-3 h-3" />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};
