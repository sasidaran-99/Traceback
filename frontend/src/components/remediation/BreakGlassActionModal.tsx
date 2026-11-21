import React, { useState } from 'react';
import {
  X,
  Lock,
  XCircle,
  MinusCircle,
  AlertTriangle,
  Shield,
  UserX,
} from 'lucide-react';
import { breakGlassApi } from '../../api/breakGlassApi';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner } from '../shared/LoadingSpinner';
import type {
  RemediationBreakGlassResponse,
  BreakGlassFinalizationResponse,
} from '../../api/types';

export type BreakGlassActionType = 'FINALIZE' | 'REJECT' | 'CANCEL';

interface BreakGlassActionModalProps {
  request: RemediationBreakGlassResponse;
  actionType: BreakGlassActionType;
  isOpen: boolean;
  onClose: () => void;
  onFinalizeSuccess: (outcome: BreakGlassFinalizationResponse) => void;
  onRequestUpdated: (updated: RemediationBreakGlassResponse) => void;
}

export const BreakGlassActionModal: React.FC<BreakGlassActionModalProps> = ({
  request,
  actionType,
  isOpen,
  onClose,
  onFinalizeSuccess,
  onRequestUpdated,
}) => {
  const { user, login } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN');

  // Check anti-self-approval (initiator vs current user)
  // Current user's identity is derived from verified JWT subject and issuer
  const isInitiator =
    user?.subject === request.initiatorSubject &&
    (user?.issuer === request.initiatorIssuer || !user?.issuer);

  const [notesOrReason, setNotesOrReason] = useState<string>('');
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSwitchToBob = async () => {
    try {
      await login('BOB_ADMIN');
      setError(null);
    } catch {
      // handled in context
    }
  };

  const handleSwitchToAlice = async () => {
    try {
      await login('ALICE_ADMIN');
      setError(null);
    } catch {
      // handled in context
    }
  };

  const validate = (): string | null => {
    if (!isAdmin) {
      return 'Action requires ROLE_ADMIN authority. Break-glass operations are restricted to administrators.';
    }

    if (actionType === 'FINALIZE') {
      if (isInitiator) {
        return 'Anti-self-approval violation: Proposer cannot approve their own break-glass request. A different administrator must finalize.';
      }
      if (notesOrReason.trim().length > 2000) {
        return 'Execution notes must not exceed 2000 characters.';
      }
    } else if (actionType === 'REJECT') {
      if (isInitiator) {
        return 'Anti-self-approval violation: Proposer cannot reject their own break-glass request. Only a peer reviewer can reject.';
      }
      if (notesOrReason.trim().length < 5) {
        return 'Rejection reason is required (minimum 5 characters).';
      }
      if (notesOrReason.trim().length > 2000) {
        return 'Rejection reason must not exceed 2000 characters.';
      }
    } else if (actionType === 'CANCEL') {
      if (!isInitiator) {
        return 'Authorization violation: Only the proposal initiator can cancel this break-glass request.';
      }
      if (notesOrReason.trim().length < 5) {
        return 'Cancellation reason is required (minimum 5 characters).';
      }
      if (notesOrReason.trim().length > 2000) {
        return 'Cancellation reason must not exceed 2000 characters.';
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
      if (actionType === 'FINALIZE') {
        const outcome = await breakGlassApi.finalizeKeepFrozen(
          request.operationId,
          request.id,
          {
            decision: 'KEEP_FROZEN',
            executionNotes: notesOrReason.trim() ? notesOrReason.trim() : undefined,
          }
        );
        onFinalizeSuccess(outcome);
        onClose();
      } else if (actionType === 'REJECT') {
        const updated = await breakGlassApi.rejectRequest(
          request.operationId,
          request.id,
          {
            rejectionReason: notesOrReason.trim(),
          }
        );
        onRequestUpdated(updated);
        onClose();
      } else if (actionType === 'CANCEL') {
        const updated = await breakGlassApi.cancelRequest(
          request.operationId,
          request.id,
          {
            cancellationReason: notesOrReason.trim(),
          }
        );
        onRequestUpdated(updated);
        onClose();
      }
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError(`Failed to perform ${actionType.toLowerCase()} action`);
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // Titles and configuration
  const config = {
    FINALIZE: {
      title: 'Finalize Break-Glass Decision: KEEP_FROZEN',
      icon: Lock,
      iconColor: 'text-[#7FA3C4]',
      badgeColor: 'bg-[#3A6C96]/15 border-[#3A6C96]/30 text-[#7FA3C4]',
      submitText: 'Confirm & Finalize: KEEP FROZEN',
      submitBg: 'bg-[#3A6C96] hover:bg-[#457CAB]',
      placeholder: 'Enter optional execution notes / audit logging rationale...',
      label: 'Execution Notes (Optional)',
      minChars: 0,
    },
    REJECT: {
      title: 'Reject Break-Glass Recovery Proposal',
      icon: XCircle,
      iconColor: 'text-[#C86A6A]',
      badgeColor: 'bg-[#C86A6A]/15 border-[#C86A6A]/30 text-[#C86A6A]',
      submitText: 'Confirm Proposal Rejection',
      submitBg: 'bg-[#C86A6A] hover:bg-[#D67B7B]',
      placeholder: 'Explain why this proposal is rejected (minimum 5 characters)...',
      label: 'Rejection Reason (Required, min 5 chars)',
      minChars: 5,
    },
    CANCEL: {
      title: 'Cancel Break-Glass Recovery Proposal',
      icon: MinusCircle,
      iconColor: 'text-[#A7B0BC]',
      badgeColor: 'bg-[#2A323C]/50 border-[#2A323C] text-[#A7B0BC]',
      submitText: 'Confirm Proposal Cancellation',
      submitBg: 'bg-[#2A323C] hover:bg-[#343E4B] text-[#E4E8EE]',
      placeholder: 'Explain why you are withdrawing this proposal (minimum 5 characters)...',
      label: 'Cancellation Reason (Required, min 5 chars)',
      minChars: 5,
    },
  }[actionType];

  const Icon = config.icon;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs font-sans">
      <div className="bg-[#0E1216] border border-[#1F262E] w-full max-w-xl rounded-lg shadow-2xl flex flex-col max-h-[90vh] overflow-hidden text-xs">
        {/* Modal Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#1F262E] bg-[#141A20]/60">
          <div className="flex items-center gap-2.5">
            <div className={`p-1.5 rounded border ${config.badgeColor}`}>
              <Icon className={`w-4 h-4 ${config.iconColor}`} />
            </div>
            <div>
              <h2 className="text-sm font-semibold text-[#E4E8EE]">{config.title}</h2>
              <p className="text-[11px] text-[#A7B0BC] font-mono">
                Request ID: {request.id.substring(0, 8)}... | Operation: {request.operationId.substring(0, 8)}...
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
          {/* Critical Safety Notice for Finalize */}
          {actionType === 'FINALIZE' && (
            <div className="p-3.5 rounded bg-[#141A20] border border-[#3A6C96]/40 space-y-2">
              <div className="flex items-center gap-2 text-[#7FA3C4] font-semibold text-xs">
                <Shield className="w-4 h-4" />
                <span>Critical Recovery & Safety Language</span>
              </div>
              <p className="text-[#E4E8EE] leading-relaxed text-[11px] font-medium">
                Break-glass decision recorded. The repository ref remains frozen. No Git recovery was performed.
              </p>
              <div className="grid grid-cols-2 gap-2 pt-1 font-mono text-[10px]">
                <div className="p-1.5 rounded bg-[#0A0D11] border border-[#1F262E]">
                  <span className="text-[#6B7585] block">Git Recovery:</span>
                  <span className="text-[#C86A6A] font-bold">NO (Never Executed)</span>
                </div>
                <div className="p-1.5 rounded bg-[#0A0D11] border border-[#1F262E]">
                  <span className="text-[#6B7585] block">Target Ref Status:</span>
                  <span className="text-[#7FA3C4] font-bold">REMAINS FROZEN</span>
                </div>
              </div>
            </div>
          )}

          {/* Anti-Self-Approval Warning Banner */}
          {actionType === 'FINALIZE' && isInitiator && (
            <div className="p-3 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/40 space-y-2">
              <div className="flex items-center gap-2 text-[#C86A6A] font-semibold">
                <UserX className="w-4 h-4" />
                <span>Anti-Self-Approval Restriction</span>
              </div>
              <p className="text-[#A7B0BC] leading-relaxed text-[11px]">
                You proposed this review as <strong className="text-[#E4E8EE]">{request.initiatorSubject}</strong>.
                The backend dual-custody policy strictly prohibits an initiator from finalizing their own request.
              </p>
              <div className="pt-1 flex items-center gap-2">
                <button
                  type="button"
                  onClick={handleSwitchToBob}
                  className="px-2.5 py-1 rounded text-[11px] font-semibold bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors"
                >
                  Switch to Bob Admin (BOB_ADMIN)
                </button>
                <span className="text-[10px] text-[#6B7585]">to approve as peer administrator</span>
              </div>
            </div>
          )}

          {/* Cancellation by Non-Initiator Warning */}
          {actionType === 'CANCEL' && !isInitiator && (
            <div className="p-3 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/40 space-y-2">
              <div className="flex items-center gap-2 text-[#C86A6A] font-semibold">
                <UserX className="w-4 h-4" />
                <span>Initiator Authorization Required</span>
              </div>
              <p className="text-[#A7B0BC] leading-relaxed text-[11px]">
                Only the proposal initiator (<strong className="text-[#E4E8EE]">{request.initiatorSubject}</strong>)
                can cancel this request. You are currently authenticated as{' '}
                <strong className="text-[#E4E8EE]">{user?.subject || 'unknown'}</strong>.
              </p>
              <div className="pt-1 flex items-center gap-2">
                <button
                  type="button"
                  onClick={handleSwitchToAlice}
                  className="px-2.5 py-1 rounded text-[11px] font-semibold bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors"
                >
                  Switch to Alice Admin (ALICE_ADMIN)
                </button>
              </div>
            </div>
          )}

          {/* Proposal Summary Details Box */}
          <div className="p-3 rounded bg-[#0A0D11] border border-[#1F262E] space-y-1.5 font-mono text-[11px]">
            <div className="flex items-center justify-between text-[#6B7585]">
              <span>Initiator:</span>
              <span className="text-[#E4E8EE]">
                {request.initiatorIssuer}:{request.initiatorSubject}
              </span>
            </div>
            <div className="flex items-center justify-between text-[#6B7585]">
              <span>Target Git Ref:</span>
              <span className="text-[#7FA3C4]">{request.targetRef}</span>
            </div>
            <div className="flex items-center justify-between text-[#6B7585]">
              <span>Proposal Rationale:</span>
              <span className="text-[#A7B0BC] truncate max-w-[280px]" title={request.rationale}>
                {request.rationale}
              </span>
            </div>
          </div>

          {/* Notes or Reason Textarea */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label htmlFor="action-notes" className="block text-xs font-semibold text-[#E4E8EE]">
                {config.label}
              </label>
              <span className="font-mono text-[10px] text-[#6B7585]">
                {notesOrReason.length} / 2000 chars
              </span>
            </div>
            <textarea
              id="action-notes"
              value={notesOrReason}
              onChange={(e) => setNotesOrReason(e.target.value)}
              placeholder={config.placeholder}
              rows={3}
              maxLength={2000}
              disabled={isSubmitting}
              className="w-full px-3 py-2 rounded bg-[#141A20] border border-[#2A323C] focus:border-[#3A6C96] focus:outline-hidden text-[#E4E8EE] placeholder-[#6B7585] text-xs resize-y"
            />
          </div>

          {/* Error Message */}
          {error && (
            <div className="p-3 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/40 text-[#C86A6A] flex items-start gap-2 text-xs">
              <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
              <div className="flex-1">
                <span className="font-semibold block mb-0.5">Operation Error</span>
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
              Close
            </button>
            <button
              type="submit"
              disabled={
                isSubmitting ||
                !isAdmin ||
                (actionType === 'FINALIZE' && isInitiator) ||
                (actionType === 'REJECT' && isInitiator) ||
                (actionType === 'CANCEL' && !isInitiator)
              }
              className={`flex items-center gap-1.5 px-4 py-1.5 rounded text-xs font-semibold text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors ${config.submitBg}`}
            >
              {isSubmitting ? (
                <>
                  <LoadingSpinner size="sm" />
                  <span>Processing...</span>
                </>
              ) : (
                <>
                  <Icon className="w-3.5 h-3.5" />
                  <span>{config.submitText}</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
