import React, { useState } from 'react';
import {
  X,
  Shield,
  AlertTriangle,
  Lock,
  KeyRound,
} from 'lucide-react';
import { breakGlassApi } from '../../api/breakGlassApi';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner } from '../shared/LoadingSpinner';
import type { RemediationOperationResponse, RemediationBreakGlassResponse } from '../../api/types';

interface BreakGlassProposalModalProps {
  operation: RemediationOperationResponse;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (newRequest: RemediationBreakGlassResponse) => void;
}

export const BreakGlassProposalModal: React.FC<BreakGlassProposalModalProps> = ({
  operation,
  isOpen,
  onClose,
  onSuccess,
}) => {
  const { user } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN');

  const [rationale, setRationale] = useState<string>('');
  const [includeEvidenceTriad, setIncludeEvidenceTriad] = useState<boolean>(false);
  const [evidenceDigest, setEvidenceDigest] = useState<string>('');
  const [evidenceBundleRef, setEvidenceBundleRef] = useState<string>('');
  const [evidenceBundleVersion, setEvidenceBundleVersion] = useState<string>('');

  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const validate = (): string | null => {
    if (!isAdmin) {
      return 'Action requires ROLE_ADMIN authority. Proposing break-glass review is restricted to administrators.';
    }

    const trimmedRationale = rationale.trim();
    if (!trimmedRationale) {
      return 'Proposal rationale is required.';
    }
    if (trimmedRationale.length < 10) {
      return 'Proposal rationale must be at least 10 characters.';
    }
    if (trimmedRationale.length > 2000) {
      return 'Proposal rationale must not exceed 2000 characters.';
    }

    if (includeEvidenceTriad) {
      const hasDigest = !!evidenceDigest.trim();
      const hasRef = !!evidenceBundleRef.trim();
      const hasVer = !!evidenceBundleVersion.trim();

      const anyPresent = hasDigest || hasRef || hasVer;
      const allPresent = hasDigest && hasRef && hasVer;

      if (anyPresent && !allPresent) {
        return 'Evidence Triad Invariant: Digest, Bundle Reference, and Version must all be provided together, or all omitted.';
      }

      if (hasDigest) {
        const hexRegex = /^[0-9a-fA-F]{64}$/;
        if (!hexRegex.test(evidenceDigest.trim())) {
          return 'Evidence digest must be exactly 64 hexadecimal characters (SHA-256).';
        }
      }
      if (hasRef && evidenceBundleRef.trim().length > 255) {
        return 'Evidence bundle reference must not exceed 255 characters.';
      }
      if (hasVer && evidenceBundleVersion.trim().length > 64) {
        return 'Evidence bundle version must not exceed 64 characters.';
      }
    }

    return null;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const validationError = validate();
    if (validationError) {
      setError(validationError);
      return;
    }

    setIsSubmitting(true);
    setError(null);

    try {
      const payload = {
        rationale: rationale.trim(),
        evidenceDigest: includeEvidenceTriad && evidenceDigest.trim() ? evidenceDigest.trim() : undefined,
        evidenceBundleRef: includeEvidenceTriad && evidenceBundleRef.trim() ? evidenceBundleRef.trim() : undefined,
        evidenceBundleVersion:
          includeEvidenceTriad && evidenceBundleVersion.trim() ? evidenceBundleVersion.trim() : undefined,
      };

      const response = await breakGlassApi.proposeRequest(operation.id, payload);
      onSuccess(response);
      onClose();
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to propose break-glass recovery request');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs font-sans">
      <div className="bg-[#0E1216] border border-[#1F262E] w-full max-w-2xl rounded-lg shadow-2xl flex flex-col max-h-[90vh] overflow-hidden text-xs">
        {/* Modal Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#1F262E] bg-[#141A20]/60">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded bg-[#C9A24A]/10 border border-[#C9A24A]/30 text-[#C9A24A]">
              <Shield className="w-4 h-4" />
            </div>
            <div>
              <h2 className="text-sm font-semibold text-[#E4E8EE]">
                Propose Dual-Custody Break-Glass Decision
              </h2>
              <p className="text-[11px] text-[#A7B0BC] font-mono">
                Operation: {operation.id.substring(0, 8)}... (RECOVERY_REQUIRED)
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            disabled={isSubmitting}
            className="p-1 rounded text-[#6B7585] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Modal Body */}
        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto p-5 space-y-4">
          {/* Dual-Custody Notice */}
          <div className="p-3 rounded bg-[#3A6C96]/10 border border-[#3A6C96]/30 space-y-1.5">
            <div className="flex items-center gap-1.5 text-[#7FA3C4] font-semibold">
              <KeyRound className="w-3.5 h-3.5" />
              <span>Dual-Custody Security Guardrail</span>
            </div>
            <p className="text-[#A7B0BC] leading-relaxed text-[11px]">
              Submitting this proposal initiates a multi-administrator workflow. A{' '}
              <strong className="text-[#E4E8EE]">different administrator</strong> must review and
              finalize this request. You will not be permitted to approve your own proposal.
            </p>
          </div>

          {/* Operation Immutable Context Box */}
          <div className="p-3.5 rounded bg-[#0A0D11] border border-[#1F262E] space-y-2.5 font-mono">
            <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block">
              Immutable Target Context
            </span>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px]">
              <div>
                <span className="text-[#6B7585]">Target Ref:</span>{' '}
                <span className="text-[#E4E8EE] font-semibold">{operation.targetRef}</span>
              </div>
              <div>
                <span className="text-[#6B7585]">Fencing Token:</span>{' '}
                <span className="text-[#7FA3C4] font-semibold">{operation.fencingToken ?? 'N/A'}</span>
              </div>
              <div>
                <span className="text-[#6B7585]">Old Commit SHA:</span>{' '}
                <span className="text-[#A7B0BC]">
                  {operation.expectedOldSha ? operation.expectedOldSha.substring(0, 10) : 'None'}
                </span>
              </div>
              <div>
                <span className="text-[#6B7585]">Proposed SHA:</span>{' '}
                <span className="text-[#A7B0BC]">
                  {operation.proposedNewSha ? operation.proposedNewSha.substring(0, 10) : 'None'}
                </span>
              </div>
            </div>
          </div>

          {/* Decision Selection (Immutable KEEP_FROZEN) */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-[#E4E8EE]">
              Requested Decision <span className="text-[#C86A6A]">*</span>
            </label>
            <div className="p-3 rounded bg-[#141A20] border border-[#3A6C96]/40 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Lock className="w-4 h-4 text-[#7FA3C4]" />
                <div>
                  <span className="font-mono font-semibold text-[#7FA3C4]">KEEP_FROZEN</span>
                  <p className="text-[11px] text-[#A7B0BC] mt-0.5">
                    Retain target ref lock. Git recovery is NOT executed.
                  </p>
                </div>
              </div>
              <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-[#3A6C96]/20 text-[#7FA3C4] border border-[#3A6C96]/40">
                Only Permitted Decision
              </span>
            </div>
          </div>

          {/* Rationale Textarea */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label htmlFor="proposal-rationale" className="block text-xs font-semibold text-[#E4E8EE]">
                Forensic Proposal Rationale <span className="text-[#C86A6A]">*</span>
              </label>
              <span className="font-mono text-[10px] text-[#6B7585]">
                {rationale.length} / 2000 chars (min 10)
              </span>
            </div>
            <textarea
              id="proposal-rationale"
              value={rationale}
              onChange={(e) => setRationale(e.target.value)}
              placeholder="State the forensic or operational justification for retaining ref freeze and bypassing automated recovery..."
              rows={4}
              maxLength={2000}
              disabled={isSubmitting}
              className="w-full px-3 py-2 rounded bg-[#141A20] border border-[#2A323C] focus:border-[#3A6C96] focus:outline-hidden text-[#E4E8EE] placeholder-[#6B7585] text-xs resize-y"
            />
          </div>

          {/* Optional Evidence Triad Section */}
          <div className="border border-[#1F262E] rounded bg-[#0A0D11] p-3 space-y-3">
            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={includeEvidenceTriad}
                  onChange={(e) => setIncludeEvidenceTriad(e.target.checked)}
                  disabled={isSubmitting}
                  className="rounded bg-[#141A20] border-[#2A323C] text-[#3A6C96] focus:ring-0"
                />
                <span className="text-xs font-semibold text-[#E4E8EE]">
                  Attach Evidence Triad (Optional)
                </span>
              </label>
              <span className="text-[10px] font-mono text-[#6B7585]">All 3 required if attached</span>
            </div>

            {includeEvidenceTriad && (
              <div className="space-y-2.5 pt-1">
                <div>
                  <label className="block text-[11px] font-medium text-[#A7B0BC] mb-1">
                    Evidence Digest (SHA-256 Hex, 64 chars)
                  </label>
                  <input
                    type="text"
                    value={evidenceDigest}
                    onChange={(e) => setEvidenceDigest(e.target.value)}
                    placeholder="e.g. 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    maxLength={64}
                    disabled={isSubmitting}
                    className="w-full px-2.5 py-1.5 rounded bg-[#141A20] border border-[#2A323C] focus:border-[#3A6C96] focus:outline-hidden text-[#E4E8EE] font-mono text-[11px]"
                  />
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <div>
                    <label className="block text-[11px] font-medium text-[#A7B0BC] mb-1">
                      Evidence Bundle Ref (max 255)
                    </label>
                    <input
                      type="text"
                      value={evidenceBundleRef}
                      onChange={(e) => setEvidenceBundleRef(e.target.value)}
                      placeholder="e.g. s3://audit/bundle-001.tar.gz"
                      maxLength={255}
                      disabled={isSubmitting}
                      className="w-full px-2.5 py-1.5 rounded bg-[#141A20] border border-[#2A323C] focus:border-[#3A6C96] focus:outline-hidden text-[#E4E8EE] font-mono text-[11px]"
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] font-medium text-[#A7B0BC] mb-1">
                      Evidence Bundle Version (max 64)
                    </label>
                    <input
                      type="text"
                      value={evidenceBundleVersion}
                      onChange={(e) => setEvidenceBundleVersion(e.target.value)}
                      placeholder="e.g. v1.0.0"
                      maxLength={64}
                      disabled={isSubmitting}
                      className="w-full px-2.5 py-1.5 rounded bg-[#141A20] border border-[#2A323C] focus:border-[#3A6C96] focus:outline-hidden text-[#E4E8EE] font-mono text-[11px]"
                    />
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Error Message */}
          {error && (
            <div className="p-3 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/40 text-[#C86A6A] flex items-start gap-2 text-xs">
              <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
              <div className="flex-1">
                <span className="font-semibold block mb-0.5">Proposal Submission Error</span>
                <span>{error}</span>
              </div>
            </div>
          )}

          {/* Action Buttons */}
          <div className="flex items-center justify-end gap-3 pt-3 border-t border-[#1F262E]">
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              className="px-3.5 py-1.5 rounded text-xs font-medium border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting || !isAdmin}
              className="flex items-center gap-1.5 px-4 py-1.5 rounded text-xs font-semibold bg-[#3A6C96] hover:bg-[#457CAB] text-white disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {isSubmitting ? (
                <>
                  <LoadingSpinner size="sm" />
                  <span>Submitting Proposal...</span>
                </>
              ) : (
                <>
                  <Shield className="w-3.5 h-3.5" />
                  <span>Submit Break-Glass Proposal</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
