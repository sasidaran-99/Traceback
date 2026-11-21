import React, { useState, useMemo, useEffect } from 'react';
import {
  Copy,
  Check,
  ChevronDown,
  ChevronUp,
  Hash,
  Search,
  X,
  ExternalLink,
} from 'lucide-react';
import { Badge } from '../shared/Badge';
import { formatDate } from '../../utils/formatters';
import type { EvidenceItem } from '../../api/types';

interface EvidenceExplorerProps {
  evidenceItems: EvidenceItem[];
  initialSearchTerm?: string;
  highlightedEvidenceId?: string;
  onClearHighlight?: () => void;
  preserveInventory?: boolean;
  onInspectEvidence?: (item: EvidenceItem) => void;
}

const TYPE_LABELS: Record<string, string> = {
  ALL: 'All Items',
  WORKING_TREE_CHANGE: 'Working Tree Changes',
  COMMIT: 'Commits',
  COMMIT_DIFF: 'Commit Diffs',
  CONFIG_DIFF: 'Config Diffs',
  STACK_TRACE: 'Stack Traces',
  STACK_FRAME: 'Stack Frames',
  LOG_ERROR: 'Log Errors',
  ERROR_SIGNATURE: 'Error Signatures',
};

export const EvidenceExplorer: React.FC<EvidenceExplorerProps> = ({
  evidenceItems,
  initialSearchTerm = '',
  highlightedEvidenceId,
  onClearHighlight,
  preserveInventory = false,
  onInspectEvidence,
}) => {
  const [selectedType, setSelectedType] = useState<string>('ALL');
  const [searchTerm, setSearchTerm] = useState<string>(
    preserveInventory ? (initialSearchTerm || '') : (initialSearchTerm || highlightedEvidenceId || '')
  );
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [copiedPayloadId, setCopiedPayloadId] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Record<string, boolean>>({});

  // Sync state when highlightedEvidenceId or initialSearchTerm changes
  useEffect(() => {
    if (highlightedEvidenceId) {
      if (!preserveInventory) {
        setSearchTerm(highlightedEvidenceId);
        setSelectedType('ALL');
      }
      setExpandedIds((prev) => ({ ...prev, [highlightedEvidenceId]: true }));

      const timer = setTimeout(() => {
        const el = document.getElementById(`evidence-card-${highlightedEvidenceId}`);
        if (el && typeof el.scrollIntoView === 'function') {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      }, 100);
      return () => clearTimeout(timer);
    } else if (initialSearchTerm) {
      setSearchTerm(initialSearchTerm);
    }
  }, [highlightedEvidenceId, initialSearchTerm, preserveInventory]);

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

  const handleCopyPayload = async (id: string, text: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(text);
      setCopiedPayloadId(id);
      setTimeout(() => setCopiedPayloadId(null), 2000);
    } catch {
      // ignore
    }
  };

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const handleClearSelection = () => {
    setSearchTerm('');
    setSelectedType('ALL');
    onClearHighlight?.();
  };

  // Derive dynamic filter categories based on present evidence items
  const filterTabs = useMemo(() => {
    const typesPresent = new Set<string>();
    evidenceItems.forEach((item) => {
      if (item.evidenceType) {
        typesPresent.add(item.evidenceType);
      }
    });

    const tabs: Array<{ label: string; value: string; count: number }> = [
      { label: 'All Items', value: 'ALL', count: evidenceItems.length },
    ];

    const order = [
      'WORKING_TREE_CHANGE',
      'COMMIT',
      'COMMIT_DIFF',
      'CONFIG_DIFF',
      'STACK_TRACE',
      'STACK_FRAME',
      'LOG_ERROR',
      'ERROR_SIGNATURE',
    ];

    const sortedTypes = Array.from(typesPresent).sort((a, b) => {
      const idxA = order.indexOf(a);
      const idxB = order.indexOf(b);
      if (idxA !== -1 && idxB !== -1) return idxA - idxB;
      if (idxA !== -1) return -1;
      if (idxB !== -1) return 1;
      return a.localeCompare(b);
    });

    for (const t of sortedTypes) {
      const count = evidenceItems.filter((e) => e.evidenceType === t).length;
      tabs.push({
        label: TYPE_LABELS[t] || t.replace(/_/g, ' '),
        value: t,
        count,
      });
    }

    return tabs;
  }, [evidenceItems]);

  const filtered = useMemo(() => {
    return evidenceItems.filter((item) => {
      const matchesType = selectedType === 'ALL' || item.evidenceType === selectedType;
      if (!matchesType) return false;

      if (!searchTerm.trim()) return true;
      const term = searchTerm.toLowerCase();
      return (
        item.evidenceId.toLowerCase().includes(term) ||
        (item.summary || '').toLowerCase().includes(term) ||
        (item.originalReference || '').toLowerCase().includes(term) ||
        (item.source || '').toLowerCase().includes(term)
      );
    });
  }, [evidenceItems, selectedType, searchTerm]);

  const getBadgeVariant = (type: string) => {
    const norm = type.toUpperCase();
    if (norm.includes('WORKING_TREE')) return 'orange';
    if (norm.includes('COMMIT')) return 'amber';
    if (norm.includes('CONFIG')) return 'blue';
    if (norm.includes('STACK') || norm.includes('ERROR')) return 'rose';
    return 'blue';
  };

  if (!evidenceItems || evidenceItems.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-[#1F262E] bg-[#141A20] text-center text-[#6B7585] text-xs">
        No evidence items recorded in the evidence registry for this investigation.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Search and Filters Bar */}
      <div className="space-y-3">
        {/* Dynamic Type Filter Tabs */}
        <div className="flex flex-wrap items-center gap-1.5 p-1 rounded-xl bg-[#141A20] border border-[#1F262E]">
          {filterTabs.map((t) => (
            <button
              key={t.value}
              onClick={() => setSelectedType(t.value)}
              className={`px-3 py-1.5 rounded-lg text-xs font-mono font-medium transition-colors flex items-center gap-1.5 ${
                selectedType === t.value
                  ? 'bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C] shadow-xs'
                  : 'text-[#6B7585] hover:text-[#E4E8EE]'
              }`}
            >
              <span>{t.label}</span>
              <span className="text-[10px] px-1.5 py-0.2 rounded-full bg-[#0C1014] text-[#6B7585] border border-[#1F262E] tabular-nums">
                {t.count}
              </span>
            </button>
          ))}
        </div>

        {/* Search Bar */}
        <div className="relative">
          <Search className="w-4 h-4 text-[#6B7585] absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search evidence by ID, filename, summary, or reference..."
            className="w-full pl-9 pr-9 py-2 rounded-lg border border-[#1F262E] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#6B7585] focus:outline-none focus:border-[#7FA3C4] transition-colors"
          />
          {searchTerm && (
            <button
              type="button"
              onClick={handleClearSelection}
              className="absolute right-2.5 top-2.5 p-0.5 rounded text-[#6B7585] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors"
              title="Clear search"
              aria-label="Clear search input"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* Results Count & Active Filter Indicator */}
      <div className="text-[11px] font-mono text-[#6B7585] flex flex-wrap items-center justify-between gap-2 px-1">
        <span>
          Showing {filtered.length} of {evidenceItems.length} evidence items
          {selectedType !== 'ALL' && ` (filtered by ${TYPE_LABELS[selectedType] || selectedType})`}
        </span>
        {highlightedEvidenceId && (
          <div className="flex items-center gap-2">
            <span className="text-[#7FA3C4] flex items-center gap-1">
              <span>{preserveInventory ? 'Target evidence item:' : 'Filtering for claim reference:'}</span>
              <strong className="underline">{highlightedEvidenceId}</strong>
            </span>
            <button
              type="button"
              onClick={handleClearSelection}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-[#1A2129] hover:bg-[#0C1014] text-[#A7B0BC] hover:text-[#7FA3C4] text-[10px] font-mono border border-[#2A323C] transition-colors"
              title="Reset filter and restore all evidence items"
              aria-label="Reset filter and restore all evidence items"
            >
              <X className="w-2.5 h-2.5" />
              <span>{preserveInventory ? 'Clear target' : 'Reset filter'}</span>
            </button>
          </div>
        )}
      </div>

      {/* Evidence Items List */}
      {filtered.length === 0 ? (
        <div className="p-8 rounded-xl border border-[#1F262E] bg-[#141A20] text-center text-[#6B7585] text-xs space-y-3">
          <p>
            No evidence items match your search or filter criteria
            {searchTerm ? (
              <span>
                {' '}for <strong className="text-[#E4E8EE] font-mono">"{searchTerm}"</strong>.
              </span>
            ) : '.'}
          </p>
          {searchTerm && (
            <div>
              <button
                type="button"
                onClick={handleClearSelection}
                className="px-3 py-1.5 rounded-lg bg-[#1A2129] hover:bg-[#0C1014] text-[#7FA3C4] font-mono text-xs border border-[#2A323C] transition-colors inline-flex items-center gap-1.5"
              >
                <span>Clear search and show all evidence</span>
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-2.5">
          {filtered.map((item) => {
            const isExpanded = !!expandedIds[item.evidenceId];
            const hasMetadata = item.metadata && Object.keys(item.metadata).length > 0;
            const isHighlighted = !!highlightedEvidenceId && item.evidenceId === highlightedEvidenceId;

            return (
              <div
                id={`evidence-card-${item.evidenceId}`}
                key={item.evidenceId}
                className={`rounded-xl border transition-all duration-300 overflow-hidden ${
                  isHighlighted
                    ? 'border-[#7FA3C4] ring-1 ring-[#7FA3C4]/60 bg-[#141A20] shadow-md'
                    : 'border-[#1F262E] bg-[#141A20] hover:border-[#2A323C]'
                }`}
              >
                {/* Card Header & Controls */}
                <div className="p-3.5 flex flex-wrap items-center justify-between gap-3 text-xs">
                  <div className="flex items-center gap-3">
                    {/* Copyable ID button */}
                    <button
                      type="button"
                      onClick={(e) => handleCopy(item.evidenceId, e)}
                      className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-[#0C1014] border border-[#1F262E] font-mono font-bold text-[#E4E8EE] hover:border-[#7FA3C4] focus:outline-none focus-visible:ring-1 focus-visible:ring-[#7FA3C4] transition-colors cursor-pointer"
                      title="Click to copy Evidence ID"
                      aria-label={`Copy Evidence ID ${item.evidenceId}`}
                    >
                      <span>{item.evidenceId}</span>
                      {copiedId === item.evidenceId ? (
                        <Check className="w-3 h-3 text-[#4FA88A]" />
                      ) : (
                        <Copy className="w-3 h-3 text-[#6B7585]" />
                      )}
                    </button>

                    <Badge variant={getBadgeVariant(item.evidenceType)} mono size="sm">
                      {item.evidenceType}
                    </Badge>

                    {isHighlighted && (
                      <div className="flex items-center gap-1.5">
                        <Badge variant="blue" mono size="sm" className="font-bold">
                          SELECTED FROM CLAIM
                        </Badge>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            handleClearSelection();
                          }}
                          className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-[#1A2129] hover:bg-[#0C1014] text-[#A7B0BC] hover:text-[#E4E8EE] text-[10px] font-mono border border-[#2A323C] transition-colors shadow-xs"
                          title="Clear selection and restore all evidence items"
                          aria-label="Clear selection and restore all evidence items"
                        >
                          <X className="w-2.5 h-2.5" />
                          <span>Clear selection</span>
                        </button>
                      </div>
                    )}
                  </div>

                  <div className="flex items-center gap-3">
                    <span className="text-[#6B7585] font-mono text-[11px]">
                      {item.source}
                    </span>
                    {item.timestamp && (
                      <span className="text-[#6B7585] font-mono text-[11px]">
                        {formatDate(item.timestamp)}
                      </span>
                    )}

                    {/* Inspect in Contextual Inspector button */}
                    {onInspectEvidence && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          onInspectEvidence(item);
                        }}
                        className="p-1 rounded text-[#7FA3C4] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors cursor-pointer flex items-center gap-1"
                        title="Inspect this evidence item in Contextual Inspector"
                        aria-label={`Inspect evidence ${item.evidenceId} in Contextual Inspector`}
                      >
                        <ExternalLink className="w-3.5 h-3.5" />
                        <span className="text-[11px] font-mono hidden md:inline">Inspect</span>
                      </button>
                    )}

                    {/* Semantic Accordion Trigger Button */}
                    <button
                      type="button"
                      id={`evidence-trigger-${item.evidenceId}`}
                      onClick={() => toggleExpand(item.evidenceId)}
                      aria-expanded={isExpanded}
                      aria-controls={`evidence-details-${item.evidenceId}`}
                      className="text-[#6B7585] hover:text-[#E4E8EE] focus:outline-none focus-visible:ring-1 focus-visible:ring-[#7FA3C4] rounded transition-colors p-1 cursor-pointer flex items-center gap-1"
                      aria-label={isExpanded ? `Collapse details for ${item.evidenceId}` : `Expand details for ${item.evidenceId}`}
                    >
                      <span className="text-[11px] font-mono hidden sm:inline">
                        {isExpanded ? 'Hide Details' : 'Details'}
                      </span>
                      {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                {/* Summary trigger button */}
                <button
                  type="button"
                  onClick={() => toggleExpand(item.evidenceId)}
                  aria-expanded={isExpanded}
                  aria-controls={`evidence-details-${item.evidenceId}`}
                  className="w-full text-left px-3.5 pb-3 font-sans text-xs text-[#A7B0BC] hover:text-[#E4E8EE] focus:outline-none focus-visible:ring-1 focus-visible:ring-[#7FA3C4] transition-colors select-none cursor-pointer"
                >
                  {item.summary}
                </button>

                {/* Expanded Details */}
                {isExpanded && (
                  <div
                    id={`evidence-details-${item.evidenceId}`}
                    role="region"
                    aria-labelledby={`evidence-trigger-${item.evidenceId}`}
                    className="px-3.5 pb-3.5 pt-2 border-t border-[#1F262E] bg-[#0C1014]/60 space-y-3 text-xs font-mono"
                  >
                    {/* Integrity Hash */}
                    {item.integrityHash && (
                      <div className="flex items-center gap-2 text-[11px] text-[#6B7585]">
                        <Hash className="w-3 h-3 text-[#7FA3C4]" />
                        <span>SHA-256:</span>
                        <code className="text-[#A7B0BC] font-mono">{item.integrityHash}</code>
                      </div>
                    )}

                    {/* Original Reference / Bounded Payload Container */}
                    {item.originalReference && (
                      <div className="p-2.5 rounded-lg bg-[#0C1014] border border-[#1F262E] text-[11px] space-y-1.5">
                        <div className="flex items-center justify-between gap-2 pb-1 border-b border-[#1F262E]">
                          <span className="text-[#6B7585] font-semibold uppercase tracking-wider text-[10px] block">
                            Original Reference / Evidence Payload
                          </span>
                          <button
                            type="button"
                            onClick={(e) => handleCopyPayload(item.evidenceId, item.originalReference || '', e)}
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-[#1A2129] hover:bg-[#2A323C] text-[#A7B0BC] hover:text-[#E4E8EE] text-[10px] font-mono border border-[#2A323C] focus:outline-none focus-visible:ring-1 focus-visible:ring-[#7FA3C4] transition-colors cursor-pointer"
                            title="Copy raw evidence payload"
                            aria-label={`Copy raw evidence payload for ${item.evidenceId}`}
                          >
                            {copiedPayloadId === item.evidenceId ? (
                              <>
                                <Check className="w-3 h-3 text-[#4FA88A]" />
                                <span className="text-[#4FA88A]">Copied</span>
                              </>
                            ) : (
                              <>
                                <Copy className="w-3 h-3 text-[#6B7585]" />
                                <span>Copy Payload</span>
                              </>
                            )}
                          </button>
                        </div>
                        <div className="max-h-96 overflow-x-auto overflow-y-auto rounded bg-[#080B0E] p-2.5 border border-[#1F262E]">
                          <pre className="text-[#E4E8EE] font-mono text-xs whitespace-pre leading-relaxed select-text">
                            {item.originalReference}
                          </pre>
                        </div>
                      </div>
                    )}

                    {/* Metadata attributes */}
                    {hasMetadata && (
                      <div className="p-2.5 rounded bg-[#0C1014] border border-[#1F262E] space-y-1.5 text-[11px]">
                        <span className="text-[#6B7585] font-bold block uppercase tracking-wider text-[10px]">
                          Artifact Metadata
                        </span>
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1">
                          {Object.entries(item.metadata || {}).map(([k, v]) => (
                            <div key={k} className="flex items-baseline justify-between gap-2">
                              <span className="text-[#6B7585]">{k}:</span>
                              <span className="text-[#A7B0BC] font-mono truncate">
                                {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                              </span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
