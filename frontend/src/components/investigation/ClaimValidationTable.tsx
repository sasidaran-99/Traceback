import React from 'react';
import { ShieldCheck } from 'lucide-react';
import { StatusPill } from '../shared/StatusPill';
import { Badge } from '../shared/Badge';
import { formatDate } from '../../utils/formatters';
import type { InvestigationClaim } from '../../api/types';

interface ClaimValidationTableProps {
  claims: InvestigationClaim[];
}

export const ClaimValidationTable: React.FC<ClaimValidationTableProps> = ({ claims }) => {
  if (!claims || claims.length === 0) {
    return (
      <div className="p-6 rounded-xl border border-dark-700 bg-dark-900 text-center text-dark-400 text-xs">
        No claims validated for this investigation.
      </div>
    );
  }

  const supportedCount = claims.filter((c) => c.status === 'SUPPORTED').length;
  const partialCount = claims.filter((c) => c.status === 'PARTIALLY_SUPPORTED').length;
  const unsupportedCount = claims.filter((c) => c.status === 'UNSUPPORTED' || c.status === 'CONTRADICTED').length;

  return (
    <div className="space-y-4">
      {/* Metrics Banner */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-dark-700 bg-dark-900/80 text-xs">
        <div className="flex items-center gap-2">
          <ShieldCheck className="w-4 h-4 text-emerald-400" />
          <span className="font-mono font-semibold text-dark-200">Claim Validation Grounding Engine</span>
        </div>
        <div className="flex items-center gap-3 font-mono text-xs">
          <span className="text-emerald-400">Supported: {supportedCount}</span>
          <span className="text-dark-600">|</span>
          <span className="text-amber-400">Partially Supported: {partialCount}</span>
          <span className="text-dark-600">|</span>
          <span className="text-rose-400">Unsupported/Contradicted: {unsupportedCount}</span>
        </div>
      </div>

      {/* Claims List */}
      <div className="space-y-3">
        {claims.map((claim) => {
          const isSupported = claim.status === 'SUPPORTED';
          const isPartial = claim.status === 'PARTIALLY_SUPPORTED';
          const isContradicted = claim.status === 'CONTRADICTED';

          return (
            <div
              key={claim.claimId}
              className={`rounded-xl border p-4 text-xs transition-colors ${
                isSupported
                  ? 'border-emerald-500/20 bg-dark-900/90'
                  : isPartial
                  ? 'border-amber-500/20 bg-dark-900/90'
                  : isContradicted
                  ? 'border-rose-500/30 bg-rose-500/5'
                  : 'border-dark-700 bg-dark-900/60'
              }`}
            >
              <div className="flex flex-wrap items-start justify-between gap-3 mb-2.5">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-[11px] text-dark-400 bg-dark-950 px-2 py-0.5 rounded border border-dark-800">
                    {claim.claimId}
                  </span>
                  <span className="text-[11px] text-dark-500">
                    {formatDate(claim.assertedAt)}
                  </span>
                </div>
                <StatusPill status={claim.status} size="sm" />
              </div>

              {/* Statement */}
              <p className="text-sm font-sans font-medium text-dark-100 mb-3 leading-relaxed">
                "{claim.statement}"
              </p>

              {/* Supporting & Contradicting Evidence IDs */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-3">
                <div className="p-2.5 rounded-lg bg-dark-950/80 border border-dark-800">
                  <span className="text-[10px] font-mono uppercase text-dark-400 block mb-1.5 font-semibold">
                    Supporting Evidence ({claim.supportingEvidenceIds?.length || 0})
                  </span>
                  {claim.supportingEvidenceIds && claim.supportingEvidenceIds.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {claim.supportingEvidenceIds.map((id) => (
                        <Badge key={id} variant="green" mono size="sm">
                          {id}
                        </Badge>
                      ))}
                    </div>
                  ) : (
                    <span className="text-[11px] text-dark-500 italic">Zero evidence IDs cited</span>
                  )}
                </div>

                <div className="p-2.5 rounded-lg bg-dark-950/80 border border-dark-800">
                  <span className="text-[10px] font-mono uppercase text-dark-400 block mb-1.5 font-semibold">
                    Counter Evidence ({claim.contradictingEvidenceIds?.length || 0})
                  </span>
                  {claim.contradictingEvidenceIds && claim.contradictingEvidenceIds.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {claim.contradictingEvidenceIds.map((id) => (
                        <Badge key={id} variant="rose" mono size="sm">
                          {id}
                        </Badge>
                      ))}
                    </div>
                  ) : (
                    <span className="text-[11px] text-dark-500 italic">None</span>
                  )}
                </div>
              </div>

              {/* Validation Explanation */}
              <div className="p-2.5 rounded bg-dark-950 border border-dark-800 text-[11px] font-mono">
                <span className="text-dark-500">Validation Explanation: </span>
                <span className="text-dark-300">{claim.explanation}</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
