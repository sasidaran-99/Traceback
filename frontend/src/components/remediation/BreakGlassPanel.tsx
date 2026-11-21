import React, { useState, useEffect, useCallback } from 'react';
import {
  Shield,
  Lock,
  Clock,
  AlertTriangle,
  RefreshCw,
  Plus,
  ChevronDown,
  ChevronRight,
} from 'lucide-react';
import { breakGlassApi } from '../../api/breakGlassApi';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner } from '../shared/LoadingSpinner';
import { BreakGlassStatusBadge } from './BreakGlassStatusBadge';
import { BreakGlassSafetyBanner } from './BreakGlassSafetyBanner';
import { BreakGlassProposalModal } from './BreakGlassProposalModal';
import { BreakGlassActionModal, BreakGlassActionType } from './BreakGlassActionModal';
import { formatDate } from '../../utils/formatters';
import type {
  RemediationOperationResponse,
  RemediationBreakGlassResponse,
  BreakGlassFinalizationResponse,
} from '../../api/types';

interface BreakGlassPanelProps {
  operation: RemediationOperationResponse;
  onOperationReloadNeeded?: () => void;
}

export const BreakGlassPanel: React.FC<BreakGlassPanelProps> = ({
  operation,
  onOperationReloadNeeded,
}) => {
  const { user, isAuthenticated, openPersonaModal } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN');

  const [requests, setRequests] = useState<RemediationBreakGlassResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Modals state
  const [isProposalModalOpen, setIsProposalModalOpen] = useState<boolean>(false);
  const [activeActionModal, setActiveActionModal] = useState<{
    request: RemediationBreakGlassResponse;
    type: BreakGlassActionType;
  } | null>(null);

  // Expanded inspection request ID
  const [expandedRequestId, setExpandedRequestId] = useState<string | null>(null);

  // Authoritative outcome banner from latest finalization
  const [lastOutcome, setLastOutcome] = useState<BreakGlassFinalizationResponse | null>(null);

  const fetchRequests = useCallback(async () => {
    if (!operation.id) return;
    setIsLoading(true);
    setError(null);

    try {
      const data = await breakGlassApi.getRequests(operation.id);
      setRequests(data);
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to load break-glass requests');
      }
    } finally {
      setIsLoading(false);
    }
  }, [operation.id]);

  useEffect(() => {
    fetchRequests();
  }, [fetchRequests, isAuthenticated]);

  const pendingRequest = requests.find((r) => r.status === 'PENDING_SECOND_APPROVAL');

  // Local clock expiry check (does NOT manufacture EXPIRED status)
  const isPastExpiryTime = pendingRequest
    ? new Date().getTime() > new Date(pendingRequest.expiresAt).getTime()
    : false;

  const handleProposalSuccess = (newRequest: RemediationBreakGlassResponse) => {
    setRequests((prev) => [newRequest, ...prev]);
    setExpandedRequestId(newRequest.id);
    onOperationReloadNeeded?.();
  };

  const handleFinalizeSuccess = (outcome: BreakGlassFinalizationResponse) => {
    setLastOutcome(outcome);
    setRequests((prev) =>
      prev.map((r) => (r.id === outcome.request.id ? outcome.request : r))
    );
    onOperationReloadNeeded?.();
  };

  const handleRequestUpdated = (updated: RemediationBreakGlassResponse) => {
    setRequests((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
    onOperationReloadNeeded?.();
  };

  return (
    <div className="mt-3.5 pt-3.5 border-t border-[#1F262E] space-y-3 font-sans text-xs">
      {/* Panel Header */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Shield className="w-4 h-4 text-[#C9A24A]" />
          <span className="font-semibold text-[#E4E8EE]">Dual-Custody Break-Glass Recovery</span>
          <span className="font-mono text-[10px] px-1.5 py-0.2 rounded bg-[#141A20] border border-[#2A323C] text-[#A7B0BC]">
            {requests.length} {requests.length === 1 ? 'request' : 'requests'}
          </span>
        </div>

        <div className="flex items-center gap-2">
          {isAdmin && operation.status === 'RECOVERY_REQUIRED' && !pendingRequest && (
            <button
              onClick={() => setIsProposalModalOpen(true)}
              title="Propose dual-custody break-glass review"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-semibold bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors"
            >
              <Plus className="w-3.5 h-3.5" />
              <span>Propose Break-Glass Review</span>
            </button>
          )}

          <button
            onClick={fetchRequests}
            title="Refresh break-glass requests"
            className="p-1 rounded text-[#6B7585] hover:text-[#E4E8EE] hover:bg-[#141A20] transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Non-Admin Informational Notice */}
      {!isAdmin && isAuthenticated && (
        <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E] text-[11px] text-[#A7B0BC] flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Lock className="w-3.5 h-3.5 text-[#6B7585]" />
            <span>
              Signed in as <strong className="text-[#E4E8EE]">{user?.subject}</strong> (read-only
              mode: ROLE_ADMIN required for break-glass mutations).
            </span>
          </div>
          <button
            onClick={openPersonaModal}
            className="text-[#7FA3C4] hover:underline font-medium"
          >
            Switch to Admin Persona
          </button>
        </div>
      )}

      {/* Authoritative Finalization Outcome Banner */}
      {lastOutcome && (
        <BreakGlassSafetyBanner
          decisionApplied={lastOutcome.decisionApplied}
          gitRecoveryExecuted={lastOutcome.gitRecoveryExecuted}
          refFrozen={lastOutcome.refFrozen}
          operationStatus={lastOutcome.operationStatus}
          message={lastOutcome.message}
        />
      )}

      {/* Loading state */}
      {isLoading && (
        <div className="py-4 flex items-center justify-center gap-2 text-[#A7B0BC] font-mono text-[11px]">
          <LoadingSpinner size="sm" />
          <span>Synchronizing dual-custody break-glass journal...</span>
        </div>
      )}

      {/* Error state */}
      {error && (
        <div className="p-3 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/30 text-[#C86A6A] flex items-start gap-2 text-xs">
          <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
          <div className="flex-1">
            <span className="font-semibold block mb-0.5">Break-Glass Discovery Failure</span>
            <span>{error}</span>
          </div>
          <button
            onClick={fetchRequests}
            className="text-[11px] underline text-[#C86A6A] hover:text-[#E4E8EE]"
          >
            Retry
          </button>
        </div>
      )}

      {/* Active Pending Request Card */}
      {pendingRequest && (
        <div className="p-4 rounded-lg bg-[#141A20] border border-[#C9A24A]/40 space-y-3 shadow-md">
          {/* Top Bar of Pending Request */}
          <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-[#1F262E]">
            <div className="flex items-center gap-2">
              <span className="text-[10px] uppercase font-bold tracking-wider text-[#C9A24A] bg-[#C9A24A]/10 px-2 py-0.5 rounded border border-[#C9A24A]/30">
                Action Required: Second-Admin Review
              </span>
              <BreakGlassStatusBadge status={pendingRequest.status} />
            </div>

            <div className="flex items-center gap-2 text-[11px] font-mono text-[#A7B0BC]">
              <span>Expires:</span>
              <span
                className={isPastExpiryTime ? 'text-[#C86A6A] font-semibold' : 'text-[#E4E8EE]'}
                title={pendingRequest.expiresAt}
              >
                {formatDate(pendingRequest.expiresAt)}
              </span>
            </div>
          </div>

          {/* Local clock expiry alert notice (does NOT forge EXPIRED status) */}
          {isPastExpiryTime && (
            <div className="p-2.5 rounded bg-[#C9A24A]/10 border border-[#C9A24A]/30 text-[11px] text-[#C9A24A] flex items-center justify-between">
              <div className="flex items-center gap-1.5">
                <Clock className="w-3.5 h-3.5 text-[#C9A24A]" />
                <span>
                  Local clock indicates expiry time has passed. Click refresh to synchronize
                  authoritative backend status.
                </span>
              </div>
              <button
                onClick={fetchRequests}
                className="px-2 py-0.5 rounded bg-[#C9A24A]/20 hover:bg-[#C9A24A]/30 text-[#C9A24A] font-medium"
              >
                Refresh
              </button>
            </div>
          )}

          {/* Pending Details Grid */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5 text-[11px] font-mono">
            <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
              <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-0.5">
                Request UUID
              </span>
              <span className="text-[#E4E8EE] truncate block" title={pendingRequest.id}>
                {pendingRequest.id.substring(0, 10)}...
              </span>
            </div>

            <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
              <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-0.5">
                Target Reference
              </span>
              <span className="text-[#7FA3C4] font-semibold truncate block" title={pendingRequest.targetRef}>
                {pendingRequest.targetRef}
              </span>
            </div>

            <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
              <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-0.5">
                Proposer Identity
              </span>
              <span className="text-[#E4E8EE] truncate block" title={`${pendingRequest.initiatorIssuer}:${pendingRequest.initiatorSubject}`}>
                {pendingRequest.initiatorSubject}
              </span>
            </div>

            <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
              <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-0.5">
                Requested Decision
              </span>
              <span className="text-[#7FA3C4] font-bold">
                {pendingRequest.requestedDecision}
              </span>
            </div>
          </div>

          {/* Rationale Display */}
          <div className="p-2.5 rounded bg-[#0A0D11] border border-[#1F262E] space-y-1">
            <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block">
              Proposal Forensic Rationale
            </span>
            <p className="text-xs text-[#E4E8EE] leading-relaxed break-words">
              {pendingRequest.rationale}
            </p>
          </div>

          {/* Pending Request Action Buttons */}
          <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-[#1F262E]">
            <div className="text-[11px] text-[#A7B0BC]">
              {user?.subject === pendingRequest.initiatorSubject ? (
                <span className="text-[#C9A24A]">
                  You proposed this review. Anti-self-approval requires a peer administrator to finalize.
                </span>
              ) : (
                <span>
                  Second administrator review ready. Finalizing records KEEP_FROZEN (no Git recovery).
                </span>
              )}
            </div>

            <div className="flex items-center gap-2">
              {/* Cancel Button (Initiator only) */}
              {user?.subject === pendingRequest.initiatorSubject && (
                <button
                  onClick={() =>
                    setActiveActionModal({
                      request: pendingRequest,
                      type: 'CANCEL',
                    })
                  }
                  className="px-3 py-1.5 rounded text-xs font-medium border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
                >
                  Cancel Proposal
                </button>
              )}

              {/* Reject Button (Second admin) */}
              <button
                onClick={() =>
                  setActiveActionModal({
                    request: pendingRequest,
                    type: 'REJECT',
                  })
                }
                disabled={!isAdmin || user?.subject === pendingRequest.initiatorSubject}
                title={
                  user?.subject === pendingRequest.initiatorSubject
                    ? 'Proposer cannot reject their own proposal'
                    : 'Reject proposal'
                }
                className="px-3 py-1.5 rounded text-xs font-medium border border-[#C86A6A]/40 bg-[#C86A6A]/10 hover:bg-[#C86A6A]/20 text-[#C86A6A] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                Reject
              </button>

              {/* Finalize Button (Second admin) */}
              <button
                onClick={() =>
                  setActiveActionModal({
                    request: pendingRequest,
                    type: 'FINALIZE',
                  })
                }
                disabled={!isAdmin || user?.subject === pendingRequest.initiatorSubject}
                title={
                  user?.subject === pendingRequest.initiatorSubject
                    ? 'Anti-self-approval: Proposer cannot approve their own proposal'
                    : 'Finalize and record KEEP_FROZEN'
                }
                className="flex items-center gap-1.5 px-3.5 py-1.5 rounded text-xs font-semibold bg-[#3A6C96] hover:bg-[#457CAB] text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors shadow-xs"
              >
                <Lock className="w-3.5 h-3.5" />
                <span>Review & Finalize (KEEP_FROZEN)</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Historical Requests Section */}
      {requests.length > 0 && (
        <div className="space-y-2 pt-1">
          <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block">
            Break-Glass Audit History ({requests.length})
          </span>

          <div className="space-y-2">
            {requests.map((req) => {
              const isExpanded = expandedRequestId === req.id;
              const shortId = req.id.substring(0, 8);

              return (
                <div
                  key={req.id}
                  className="rounded border border-[#1F262E] bg-[#0A0D11] hover:border-[#2A323C] transition-all overflow-hidden"
                >
                  {/* Row Summary Header */}
                  <div
                    onClick={() => setExpandedRequestId(isExpanded ? null : req.id)}
                    className="p-3 flex flex-wrap items-center justify-between gap-2.5 cursor-pointer select-none"
                  >
                    <div className="flex items-center gap-2.5">
                      {isExpanded ? (
                        <ChevronDown className="w-3.5 h-3.5 text-[#6B7585]" />
                      ) : (
                        <ChevronRight className="w-3.5 h-3.5 text-[#6B7585]" />
                      )}
                      <BreakGlassStatusBadge status={req.status} />
                      <span className="font-mono text-[11px] text-[#A7B0BC]">
                        {shortId}...
                      </span>
                      <span className="text-[#6B7585]">•</span>
                      <span className="font-mono text-[11px] text-[#7FA3C4]">
                        {req.targetRef}
                      </span>
                    </div>

                    <div className="flex items-center gap-3 text-[11px] font-mono text-[#6B7585]">
                      <span>Initiator: {req.initiatorSubject}</span>
                      <span>•</span>
                      <span>{formatDate(req.createdAt)}</span>
                    </div>
                  </div>

                  {/* Expanded Detail View */}
                  {isExpanded && (
                    <div className="p-3.5 border-t border-[#1F262E]/60 bg-[#0E1216] space-y-3 text-xs">
                      {/* Metadata Grid */}
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2 text-[11px] font-mono">
                        <div>
                          <span className="text-[#6B7585]">Full Request UUID:</span>
                          <p className="text-[#E4E8EE] break-all">{req.id}</p>
                        </div>
                        <div>
                          <span className="text-[#6B7585]">Target Ref:</span>
                          <p className="text-[#7FA3C4]">{req.targetRef}</p>
                        </div>
                        <div>
                          <span className="text-[#6B7585]">Operation Fencing Token:</span>
                          <p className="text-[#E4E8EE]">{req.operationFencingToken ?? 'N/A'}</p>
                        </div>
                        <div>
                          <span className="text-[#6B7585]">Expected Old Commit SHA:</span>
                          <p className="text-[#A7B0BC] truncate" title={req.expectedOldSha}>
                            {req.expectedOldSha || 'None'}
                          </p>
                        </div>
                        <div>
                          <span className="text-[#6B7585]">Proposed New Commit SHA:</span>
                          <p className="text-[#A7B0BC] truncate" title={req.proposedNewSha}>
                            {req.proposedNewSha || 'None'}
                          </p>
                        </div>
                        <div>
                          <span className="text-[#6B7585]">Snapshot Git SHA:</span>
                          <p className="text-[#A7B0BC] truncate" title={req.snapshotGitSha}>
                            {req.snapshotGitSha || 'None'}
                          </p>
                        </div>
                      </div>

                      {/* Rationale */}
                      <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E] space-y-1">
                        <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block">
                          Proposal Rationale
                        </span>
                        <p className="text-xs text-[#E4E8EE] break-words">{req.rationale}</p>
                      </div>

                      {/* Evidence Triad if present */}
                      {(req.evidenceDigest || req.evidenceBundleRef || req.evidenceBundleVersion) && (
                        <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E] space-y-1.5 font-mono text-[11px]">
                          <span className="text-[10px] uppercase tracking-wider text-[#7FA3C4] font-semibold block">
                            Cryptographic Evidence Triad
                          </span>
                          <div className="grid grid-cols-1 gap-1">
                            <div>
                              <span className="text-[#6B7585]">Digest:</span>{' '}
                              <span className="text-[#4FA88A] break-all">{req.evidenceDigest}</span>
                            </div>
                            <div>
                              <span className="text-[#6B7585]">Bundle Ref:</span>{' '}
                              <span className="text-[#E4E8EE]">{req.evidenceBundleRef}</span>
                            </div>
                            <div>
                              <span className="text-[#6B7585]">Bundle Version:</span>{' '}
                              <span className="text-[#E4E8EE]">{req.evidenceBundleVersion}</span>
                            </div>
                          </div>
                        </div>
                      )}

                      {/* Resolution Details (Approver, Notes, Rejection, Cancellation) */}
                      {req.approverSubject && (
                        <div className="p-2.5 rounded bg-[#3A6C96]/10 border border-[#3A6C96]/30 space-y-1 text-xs">
                          <span className="text-[10px] uppercase tracking-wider text-[#7FA3C4] font-semibold block">
                            Resolved by Administrator
                          </span>
                          <div className="font-mono text-[11px] text-[#E4E8EE]">
                            Approver: {req.approverIssuer}:{req.approverSubject} | Resolved At:{' '}
                            {req.resolvedAt ? formatDate(req.resolvedAt) : 'N/A'}
                          </div>
                          {req.executionNotes && (
                            <p className="text-xs text-[#A7B0BC] mt-1 italic break-words">
                              Notes: &ldquo;{req.executionNotes}&rdquo;
                            </p>
                          )}
                        </div>
                      )}

                      {req.rejectionReason && (
                        <div className="p-2.5 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/30 space-y-1 text-xs">
                          <span className="text-[10px] uppercase tracking-wider text-[#C86A6A] font-semibold block">
                            Rejection Reason
                          </span>
                          <p className="text-xs text-[#E4E8EE] break-words">{req.rejectionReason}</p>
                        </div>
                      )}

                      {req.cancellationReason && (
                        <div className="p-2.5 rounded bg-[#2A323C]/40 border border-[#2A323C] space-y-1 text-xs">
                          <span className="text-[10px] uppercase tracking-wider text-[#A7B0BC] font-semibold block">
                            Cancellation by Initiator ({req.cancelledBySubject})
                          </span>
                          <p className="text-xs text-[#E4E8EE] break-words">{req.cancellationReason}</p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Empty State when no requests exist */}
      {requests.length === 0 && !isLoading && !error && (
        <div className="p-4 rounded bg-[#0A0D11] border border-[#1F262E] text-center space-y-1.5">
          <p className="text-[11px] text-[#6B7585]">
            No break-glass recovery requests recorded for this operation.
          </p>
          {operation.status === 'RECOVERY_REQUIRED' && isAdmin && (
            <p className="text-[11px] text-[#7FA3C4]">
              Operation is in <span className="font-mono">RECOVERY_REQUIRED</span>. You can propose a
              dual-custody review to record KEEP_FROZEN.
            </p>
          )}
        </div>
      )}

      {/* Proposal Modal */}
      <BreakGlassProposalModal
        operation={operation}
        isOpen={isProposalModalOpen}
        onClose={() => setIsProposalModalOpen(false)}
        onSuccess={handleProposalSuccess}
      />

      {/* Action Modal (Finalize / Reject / Cancel) */}
      {activeActionModal && (
        <BreakGlassActionModal
          request={activeActionModal.request}
          actionType={activeActionModal.type}
          isOpen={true}
          onClose={() => setActiveActionModal(null)}
          onFinalizeSuccess={handleFinalizeSuccess}
          onRequestUpdated={handleRequestUpdated}
        />
      )}
    </div>
  );
};
