import React, { useState } from 'react';
import {
  Copy,
  Check,
  ChevronDown,
  ChevronUp,
  Hash,
} from 'lucide-react';
import { Badge } from '../shared/Badge';
import { formatDate } from '../../utils/formatters';
import type { EvidenceItem } from '../../api/types';

interface EvidenceExplorerProps {
  evidenceItems: EvidenceItem[];
}

export const EvidenceExplorer: React.FC<EvidenceExplorerProps> = ({ evidenceItems }) => {
  const [selectedType, setSelectedType] = useState<string>('ALL');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Record<string, boolean>>({});

  const handleCopy = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(id);
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // ignore
    }
  };

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const types: Array<{ label: string; value: string }> = [
    { label: 'All Items', value: 'ALL' },
    { label: 'Commit Diffs', value: 'COMMIT_DIFF' },
    { label: 'Config Diffs', value: 'CONFIG_DIFF' },
    { label: 'Stack Traces', value: 'STACK_TRACE' },
    { label: 'Log Errors', value: 'LOG_ERROR' },
    { label: 'Error Signatures', value: 'ERROR_SIGNATURE' },
  ];

  const filtered = evidenceItems.filter((item) => {
    if (selectedType === 'ALL') return true;
    return item.evidenceType === selectedType;
  });

  if (!evidenceItems || evidenceItems.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
        No evidence items recorded in the evidence registry for this investigation.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Type Filter Tabs */}
      <div className="flex flex-wrap items-center gap-1.5 p-1 rounded-xl bg-dark-900 border border-dark-700/80">
        {types.map((t) => {
          const count =
            t.value === 'ALL'
              ? evidenceItems.length
              : evidenceItems.filter((e) => e.evidenceType === t.value).length;

          if (t.value !== 'ALL' && count === 0) return null;

          return (
            <button
              key={t.value}
              onClick={() => setSelectedType(t.value)}
              className={`px-3 py-1.5 rounded-lg text-xs font-mono font-medium transition-colors flex items-center gap-1.5 ${
                selectedType === t.value
                  ? 'bg-dark-800 text-brand-amber border border-dark-700 shadow-sm'
                  : 'text-dark-400 hover:text-dark-200'
              }`}
            >
              <span>{t.label}</span>
              <span className="text-[10px] px-1.5 py-0.2 rounded-full bg-dark-950 text-dark-400 border border-dark-800">
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {/* Evidence Items List */}
      <div className="space-y-2.5">
        {filtered.map((item) => {
          const isExpanded = !!expandedIds[item.evidenceId];
          const hasMetadata = item.metadata && Object.keys(item.metadata).length > 0;

          return (
            <div
              key={item.evidenceId}
              className="rounded-xl border border-dark-700 bg-dark-900/80 hover:border-dark-600 transition-colors overflow-hidden"
            >
              <div
                onClick={() => toggleExpand(item.evidenceId)}
                className="p-3.5 flex flex-wrap items-center justify-between gap-3 cursor-pointer select-none text-xs"
              >
                <div className="flex items-center gap-3">
                  {/* Copyable ID */}
                  <div
                    onClick={(e) => handleCopy(item.evidenceId, e)}
                    className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-dark-950 border border-dark-700 font-mono font-bold text-dark-200 hover:border-dark-500 transition-colors"
                    title="Click to copy Evidence ID"
                  >
                    <span>{item.evidenceId}</span>
                    {copiedId === item.evidenceId ? (
                      <Check className="w-3 h-3 text-emerald-400" />
                    ) : (
                      <Copy className="w-3 h-3 text-dark-500" />
                    )}
                  </div>

                  <Badge variant="amber" mono size="sm">
                    {item.evidenceType}
                  </Badge>

                  <span className="font-sans font-medium text-dark-200 truncate max-w-md sm:max-w-lg">
                    {item.summary}
                  </span>
                </div>

                <div className="flex items-center gap-3">
                  {item.source && (
                    <span className="text-[11px] font-mono text-dark-400 uppercase">
                      Src: {item.source}
                    </span>
                  )}
                  {item.timestamp && (
                    <span className="text-[11px] text-dark-500 hidden sm:inline">
                      {formatDate(item.timestamp)}
                    </span>
                  )}
                  {item.integrityHash && (
                    <div
                      className="flex items-center gap-1 text-[11px] font-mono text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20"
                      title={`Cryptographic SHA-256 Checksum: ${item.integrityHash}`}
                    >
                      <Hash className="w-3 h-3" />
                      <span>{item.integrityHash.substring(0, 8)}</span>
                    </div>
                  )}

                  <button className="text-dark-400 hover:text-dark-200">
                    {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              {/* Expanded Details */}
              {isExpanded && (
                <div className="px-4 pb-4 pt-2 border-t border-dark-800 space-y-3 bg-dark-950/60 text-xs font-mono">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-dark-300">
                    <div>
                      <span className="text-dark-500 block mb-0.5">Original Reference:</span>
                      <span className="text-dark-100 bg-dark-900 px-2 py-1 rounded border border-dark-800 block break-all">
                        {item.originalReference || 'None'}
                      </span>
                    </div>
                    {item.integrityHash && (
                      <div>
                        <span className="text-dark-500 block mb-0.5">SHA-256 Payload Hash:</span>
                        <span className="text-dark-100 bg-dark-900 px-2 py-1 rounded border border-dark-800 block break-all">
                          {item.integrityHash}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Metadata JSON */}
                  {hasMetadata && (
                    <div>
                      <span className="text-dark-500 block mb-1">Structured Metadata:</span>
                      <pre className="p-3 rounded-lg bg-dark-950 border border-dark-800 text-[11px] text-dark-200 overflow-x-auto">
                        {JSON.stringify(item.metadata, null, 2)}
                      </pre>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
