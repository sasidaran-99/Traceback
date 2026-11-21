import React, { useState } from 'react';
import { ShieldCheck, ExternalLink } from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { formatDate } from '../../utils/formatters';
import type { InvestigationClaim } from '../../api/types';

interface ClaimValidationTableProps {
  claims: InvestigationClaim[];
  onSelectEvidence?: (evidenceId: string) => void;
  onInspectClaim?: (claim: InvestigationClaim) => void;
}

export const ClaimValidationTable: React.FC<ClaimValidationTableProps> = ({
  claims,
  onSelectEvidence,
  onInspectClaim,
}) => {
  const [statusFilter, setStatusFilter] = useState<string>('ALL');

  if (!claims || claims.length === 0) {
    return (
      <div className="p-8 rounded-xl border border-[#1F262E] bg-[#141A20] text-center text-[#6B7585] text-xs">
        No claims validated for this investigation.
      </div>
    );
  }

  const supportedCount = claims.filter((c) => c.status === 'SUPPORTED').length;
  const validationErrorCount = claims.filter((c) => c.status === 'VALIDATION_ERROR').length;
  const contradictedCount = claims.filter((c) => c.status === 'CONTRADICTED').length;
  const partialCount = claims.filter((c) => c.status === 'PARTIALLY_SUPPORTED').length;
  const unsupportedCount = claims.filter((c) => c.status === 'UNSUPPORTED').length;

  const filteredClaims =
    statusFilter === 'ALL' ? claims : claims.filter((c) => c.status === statusFilter);

  return (
    <div className="space-y-4">
      {/* Metrics Banner */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-4 rounded-xl border border-[#1F262E] bg-[#141A20] text-xs shadow-sm">
        <div className="flex items-center gap-2">
          <ShieldCheck className="w-4 h-4 text-[#4FA88A]" />
          <span className="font-mono font-semibold text-[#E4E8EE]">Claim Validation Grounding Engine</span>
        </div>
        <div className="flex flex-wrap items-center gap-3 font-mono text-xs tabular-nums">
          <span className="text-[#4FA88A] font-semibold">Supported: {supportedCount}</span>
          <span className="text-[#2A323C]">|</span>
          <span className="text-[#7FA3C4] font-semibold">Validation errors: {validationErrorCount}</span>
          <span className="text-[#2A323C]">|</span>
          <span className="text-[#C86A6A] font-semibold">Contradicted: {contradictedCount}</span>
          {partialCount > 0 && (
            <>
              <span className="text-[#2A323C]">|</span>
              <span className="text-[#C9A24A] font-semibold">Partially Supported: {partialCount}</span>
            </>
          )}
          {unsupportedCount > 0 && (
            <>
              <span className="text-[#2A323C]">|</span>
              <span className="text-[#6B7585] font-semibold">Unsupported: {unsupportedCount}</span>
            </>
          )}
        </div>
      </div>

      {/* Status Filter Tabs */}
      <div className="flex flex-wrap items-center gap-1.5 p-1 rounded-lg bg-[#0C1014] border border-[#1F262E] text-xs font-mono">
        <button
          type="button"
          onClick={() => setStatusFilter('ALL')}
          className={`px-3 py-1.5 rounded-md transition-colors ${
            statusFilter === 'ALL'
              ? 'bg-[#1A2129] text-[#E4E8EE] font-semibold border border-[#2A323C]'
              : 'text-[#6B7585] hover:text-[#A7B0BC]'
          }`}
        >
          All ({claims.length})
        </button>
        <button
          type="button"
          onClick={() => setStatusFilter('SUPPORTED')}
          className={`px-3 py-1.5 rounded-md transition-colors ${
            statusFilter === 'SUPPORTED'
              ? 'bg-[#1A2129] text-[#4FA88A] font-semibold border border-[#2A323C]'
              : 'text-[#6B7585] hover:text-[#4FA88A]'
          }`}
        >
          Supported ({supportedCount})
        </button>
        <button
          type="button"
          onClick={() => setStatusFilter('VALIDATION_ERROR')}
          className={`px-3 py-1.5 rounded-md transition-colors ${
            statusFilter === 'VALIDATION_ERROR'
              ? 'bg-[#1A2129] text-[#7FA3C4] font-semibold border border-[#2A323C]'
              : 'text-[#6B7585] hover:text-[#7FA3C4]'
          }`}
        >
          Validation Errors ({validationErrorCount})
        </button>
        <button
          type="button"
          onClick={() => setStatusFilter('CONTRADICTED')}
          className={`px-3 py-1.5 rounded-md transition-colors ${
            statusFilter === 'CONTRADICTED'
              ? 'bg-[#1A2129] text-[#C86A6A] font-semibold border border-[#2A323C]'
              : 'text-[#6B7585] hover:text-[#C86A6A]'
          }`}
        >
          Contradicted ({contradictedCount})
        </button>
        {partialCount > 0 && (
          <button
            type="button"
            onClick={() => setStatusFilter('PARTIALLY_SUPPORTED')}
            className={`px-3 py-1.5 rounded-md transition-colors ${
              statusFilter === 'PARTIALLY_SUPPORTED'
                ? 'bg-[#1A2129] text-[#C9A24A] font-semibold border border-[#2A323C]'
                : 'text-[#6B7585] hover:text-[#C9A24A]'
            }`}
          >
            Partially Supported ({partialCount})
          </button>
        )}
        <button
          type="button"
          onClick={() => setStatusFilter('UNSUPPORTED')}
          className={`px-3 py-1.5 rounded-md transition-colors ${
            statusFilter === 'UNSUPPORTED'
              ? 'bg-[#1A2129] text-[#A7B0BC] font-semibold border border-[#2A323C]'
              : 'text-[#6B7585] hover:text-[#A7B0BC]'
          }`}
        >
          Unsupported ({unsupportedCount})
        </button>
      </div>

      {/* Claims List */}
      <div className="space-y-3">
        {filteredClaims.map((claim) => {
          return (
            <div
              key={claim.claimId}
              onClick={() => onInspectClaim?.(claim)}
              className="p-4 rounded-xl border border-[#1F262E] bg-[#141A20] hover:border-[#2A323C] transition-colors space-y-3 text-xs cursor-pointer"
            >
              {/* Header: ID, Status, Timestamp */}
              <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-[#1F262E]">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-[#6B7585] text-[11px]">{claim.claimId}</span>
                  <StatusPill status={claim.status} size="sm" />
                </div>
                <div className="flex items-center gap-3">
                  {claim.assertedAt && (
                    <span className="text-[11px] font-mono text-[#6B7585]">
                      {formatDate(claim.assertedAt)}
                    </span>
                  )}
                  {onInspectClaim && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onInspectClaim(claim);
                      }}
                      className="p-1 rounded text-[#7FA3C4] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors cursor-pointer flex items-center gap-1"
                      title="Inspect this claim in Contextual Inspector"
                      aria-label={`Inspect claim ${claim.claimId} in Contextual Inspector`}
                    >
                      <ExternalLink className="w-3.5 h-3.5" />
                      <span className="text-[11px] font-mono hidden sm:inline">Inspect</span>
                    </button>
                  )}
                </div>
              </div>

              {/* Statement with ASCII quotes */}
              <div className="font-sans text-sm text-[#E4E8EE] leading-relaxed">
                "{claim.statement}"
              </div>

              {/* Explanation */}
              {claim.explanation && (
                <div className="p-3 rounded-lg bg-[#0C1014] border border-[#1F262E] font-mono text-xs text-[#A7B0BC] leading-relaxed">
                  {claim.explanation}
                </div>
              )}

              {/* Supporting & Contradicting Evidence IDs */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 pt-1">
                {/* Supporting Evidence */}
                <div className="space-y-1.5">
                  <span className="text-[11px] font-mono text-[#4FA88A] font-semibold uppercase tracking-wider block">
                    Supporting Evidence ({claim.supportingEvidenceIds?.length || 0})
                  </span>
                  {claim.supportingEvidenceIds && claim.supportingEvidenceIds.length > 0 ? (
                    <div className="flex flex-wrap gap-1.5">
                      {claim.supportingEvidenceIds.map((evId) => (
                        <button
                          key={evId}
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            onSelectEvidence?.(evId);
                          }}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              e.stopPropagation();
                              onSelectEvidence?.(evId);
                            }
                          }}
                          className="px-2 py-0.5 rounded bg-[#1A2129] text-[#4FA88A] border border-[#1F262E] font-mono text-[11px] hover:border-[#4FA88A]/40 transition-colors cursor-pointer"
                          title={`View supporting evidence ${evId}`}
                          aria-label={`View supporting evidence ${evId}`}
                        >
                          {evId}
                        </button>
                      ))}
                    </div>
                  ) : (
                    <span className="text-[#6B7585] italic font-mono text-[11px]">Zero evidence IDs cited</span>
                  )}
                </div>

                {/* Contradicting Evidence */}
                <div className="space-y-1.5">
                  <span className="text-[11px] font-mono text-[#C86A6A] font-semibold uppercase tracking-wider block">
                    Counter Evidence ({claim.contradictingEvidenceIds?.length || 0})
                  </span>
                  {claim.contradictingEvidenceIds && claim.contradictingEvidenceIds.length > 0 ? (
                    <div className="flex flex-wrap gap-1.5">
                      {claim.contradictingEvidenceIds.map((evId) => (
                        <button
                          key={evId}
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            onSelectEvidence?.(evId);
                          }}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              e.stopPropagation();
                              onSelectEvidence?.(evId);
                            }
                          }}
                          className="px-2 py-0.5 rounded bg-[#1A2129] text-[#C86A6A] border border-[#1F262E] font-mono text-[11px] hover:border-[#C86A6A]/40 transition-colors cursor-pointer"
                          title={`View counter-evidence ${evId}`}
                          aria-label={`View counter-evidence ${evId}`}
                        >
                          {evId}
                        </button>
                      ))}
                    </div>
                  ) : (
                    <span className="text-[#6B7585] italic font-mono text-[11px]">None</span>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
