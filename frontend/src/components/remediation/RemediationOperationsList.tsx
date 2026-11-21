import React, { useEffect, useState, useCallback } from 'react';
import {
  Wrench,
  GitBranch,
  Clock,
  User,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  RefreshCw,
  Copy,
  Check,
  ShieldAlert,
  ShieldCheck,
  ArrowRight,
  Hash,
} from 'lucide-react';
import { remediationApi } from '../../api/remediationApi';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner } from '../shared/LoadingSpinner';
import { formatDate } from '../../utils/formatters';
import { BreakGlassPanel } from './BreakGlassPanel';
import type { RemediationOperationResponse, RemediationOperationStatus } from '../../api/types';

interface RemediationOperationsListProps {
  investigationId: string;
  onCountChange?: (count: number) => void;
}

export const RemediationOperationsList: React.FC<RemediationOperationsListProps> = ({
  investigationId,
  onCountChange,
}) => {
  const { isAuthenticated, openPersonaModal } = useAuth();
  const [operations, setOperations] = useState<RemediationOperationResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const fetchOperations = useCallback(async () => {
    if (!investigationId || !investigationId.trim()) {
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      const data = await remediationApi.getRemediations(investigationId);
      setOperations(data);
      onCountChange?.(data.length);
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to load remediation operations');
      }
    } finally {
      setIsLoading(false);
    }
  }, [investigationId, onCountChange]);

  useEffect(() => {
    fetchOperations();
  }, [fetchOperations, isAuthenticated]);

  const copyText = async (text: string, id: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // ignore
    }
  };

  const getStatusBadge = (status: RemediationOperationStatus) => {
    switch (status) {
      case 'PERSISTED':
      case 'GIT_CAS_SUCCEEDED':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#4FA88A]/10 border border-[#4FA88A]/30 text-[#4FA88A]">
            <CheckCircle2 className="w-3 h-3" />
            {status}
          </span>
        );
      case 'FAILED':
      case 'RECOVERY_REQUIRED':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#C86A6A]/10 border border-[#C86A6A]/30 text-[#C86A6A]">
            <XCircle className="w-3 h-3" />
            {status}
          </span>
        );
      case 'CANCELLED':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#2A323C]/50 border border-[#2A323C] text-[#A7B0BC]">
            {status}
          </span>
        );
      case 'VALIDATED':
      case 'PRE_GIT_CAS_INTENT':
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#3A6C96]/20 border border-[#3A6C96]/40 text-[#7FA3C4]">
            <RefreshCw className="w-3 h-3 animate-spin" />
            {status}
          </span>
        );
      case 'CREATED':
      default:
        return (
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#C9A24A]/10 border border-[#C9A24A]/30 text-[#C9A24A]">
            <Clock className="w-3 h-3" />
            {status}
          </span>
        );
    }
  };

  // Loading State
  if (isLoading) {
    return (
      <div className="py-12 flex flex-col items-center justify-center space-y-3 bg-[#0E1216] border border-[#1F262E] rounded-lg">
        <LoadingSpinner size="md" />
        <span className="text-xs text-[#A7B0BC] font-mono">
          Querying remediation journal for investigation {investigationId}...
        </span>
      </div>
    );
  }

  // Error State
  if (error) {
    const isAuthError = error.includes('401') || error.toLowerCase().includes('unauthorized');
    const isForbiddenError = error.includes('403') || error.toLowerCase().includes('forbidden');

    return (
      <div className="p-6 rounded-lg bg-[#0E1216] border border-[#C86A6A]/30 space-y-4 font-sans">
        <div className="flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-[#C86A6A] shrink-0 mt-0.5" />
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-[#E4E8EE]">
              {isAuthError
                ? 'Authentication Required'
                : isForbiddenError
                ? 'Access Forbidden'
                : 'Remediation Discovery Failure'}
            </h3>
            <p className="text-xs text-[#A7B0BC] mt-1 leading-relaxed">
              {isAuthError
                ? 'Discovering remediation operations requires an authenticated session. Please assume a development persona (ALICE_ADMIN, BOB_ADMIN, CHARLIE_OPERATOR, or DANA_VIEWER).'
                : isForbiddenError
                ? 'Your current identity lacks the necessary roles (ROLE_ADMIN, ROLE_OPERATOR, or ROLE_VIEWER) to query remediations.'
                : error}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3 pt-2">
          {(!isAuthenticated || isAuthError) && (
            <button
              onClick={openPersonaModal}
              className="px-3 py-1.5 rounded text-xs font-medium bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors"
            >
              Sign In (Dev Persona)
            </button>
          )}
          <button
            onClick={fetchOperations}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry Query</span>
          </button>
        </div>
      </div>
    );
  }

  // Empty State
  if (operations.length === 0) {
    return (
      <div className="py-12 px-6 rounded-lg bg-[#0E1216] border border-[#1F262E] text-center space-y-3 font-sans">
        <div className="inline-flex p-3 rounded-full bg-[#141A20] border border-[#2A323C] text-[#6B7585]">
          <Wrench className="w-6 h-6 text-[#7FA3C4]" />
        </div>
        <h3 className="text-sm font-semibold text-[#E4E8EE]">No Remediation Operations Found</h3>
        <p className="text-xs text-[#6B7585] max-w-md mx-auto leading-relaxed">
          No automated or dual-custody remediation operations (branch patch promotions, rollbacks, or cherry-picks)
          have been proposed for investigation <code className="font-mono text-[#A7B0BC]">{investigationId}</code>.
        </p>
        <button
          onClick={fetchOperations}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
        >
          <RefreshCw className="w-3 h-3" />
          <span>Refresh Journal</span>
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-4 font-sans select-none">
      {/* Discovery Header / Stats Bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3 rounded-lg bg-[#0E1216] border border-[#1F262E] text-xs">
        <div className="flex items-center gap-2">
          <Wrench className="w-4 h-4 text-[#7FA3C4]" />
          <span className="font-semibold text-[#E4E8EE]">Remediation Journal</span>
          <span className="font-mono text-[11px] px-1.5 py-0.2 rounded bg-[#141A20] border border-[#2A323C] text-[#A7B0BC]">
            {operations.length} {operations.length === 1 ? 'operation' : 'operations'}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-[11px] text-[#6B7585] font-mono">Scoped to Investigation ID:</span>
          <span className="font-mono text-[11px] text-[#7FA3C4] px-1.5 py-0.5 rounded bg-[#141A20] border border-[#2A323C]">
            {investigationId.substring(0, 8)}...
          </span>
          <button
            onClick={fetchOperations}
            title="Refresh operations"
            className="p-1 rounded text-[#6B7585] hover:text-[#E4E8EE] hover:bg-[#141A20] transition-colors"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Operations List */}
      <div className="space-y-3">
        {operations.map((op) => {
          const shortId = op.id.substring(0, 8);
          const oldShaShort = op.expectedOldSha ? op.expectedOldSha.substring(0, 7) : 'None';
          const newShaShort = op.proposedNewSha ? op.proposedNewSha.substring(0, 7) : 'None';

          return (
            <div
              key={op.id}
              className="p-4 rounded-lg bg-[#0E1216] border border-[#1F262E] hover:border-[#2A323C] transition-all space-y-3 font-sans shadow-xs"
            >
              {/* Card Top Row: Type, Status, ID, Timestamps */}
              <div className="flex flex-wrap items-center justify-between gap-2.5 pb-2.5 border-b border-[#1F262E]/60 text-xs">
                <div className="flex items-center gap-2">
                  <span className="px-2 py-0.5 rounded text-[11px] font-mono font-semibold bg-[#3A6C96]/15 border border-[#3A6C96]/30 text-[#7FA3C4]">
                    {op.operationType}
                  </span>
                  {getStatusBadge(op.status)}
                </div>

                <div className="flex items-center gap-3 text-xs font-mono text-[#6B7585]">
                  <div className="flex items-center gap-1.5">
                    <span>ID:</span>
                    <span className="text-[#E4E8EE]" title={op.id}>
                      {shortId}...
                    </span>
                    <button
                      onClick={() => copyText(op.id, op.id)}
                      className="text-[#6B7585] hover:text-[#E4E8EE]"
                      title="Copy full Operation UUID"
                    >
                      {copiedId === op.id ? (
                        <Check className="w-3 h-3 text-[#4FA88A]" />
                      ) : (
                        <Copy className="w-3 h-3" />
                      )}
                    </button>
                  </div>
                  <span>•</span>
                  <span>{formatDate(op.createdAt)}</span>
                </div>
              </div>

              {/* Card Middle: Git CAS Transition & Metadata Grid */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
                {/* Target Ref */}
                <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E]/60">
                  <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block mb-1">
                    Target Reference
                  </span>
                  <div className="flex items-center gap-1.5 font-mono text-[#E4E8EE] font-medium">
                    <GitBranch className="w-3.5 h-3.5 text-[#7FA3C4] shrink-0" />
                    <span className="truncate" title={op.targetRef}>
                      {op.targetRef}
                    </span>
                  </div>
                </div>

                {/* Commit SHA CAS Transition */}
                <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E]/60">
                  <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block mb-1">
                    CAS Transition (Old → Proposed)
                  </span>
                  <div className="flex items-center gap-1.5 font-mono text-[#A7B0BC]">
                    <span title={op.expectedOldSha}>{oldShaShort}</span>
                    <ArrowRight className="w-3 h-3 text-[#6B7585]" />
                    <span className="text-[#4FA88A] font-semibold" title={op.proposedNewSha}>
                      {newShaShort}
                    </span>
                  </div>
                </div>

                {/* Fencing Token & Worker */}
                <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E]/60">
                  <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block mb-1">
                    Fencing & Worker
                  </span>
                  <div className="flex items-center justify-between font-mono text-[#A7B0BC]">
                    <div className="flex items-center gap-1">
                      <Hash className="w-3 h-3 text-[#6B7585]" />
                      <span>Token: {op.fencingToken ?? 'N/A'}</span>
                    </div>
                    <span className="text-[11px] text-[#6B7585] truncate max-w-[90px]" title={op.workerId || 'None'}>
                      {op.workerId || 'No worker'}
                    </span>
                  </div>
                </div>

                {/* Initiator & Recovery */}
                <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E]/60">
                  <span className="text-[10px] uppercase tracking-wider text-[#6B7585] font-semibold block mb-1">
                    Initiator & Recovery
                  </span>
                  <div className="flex items-center justify-between text-[#A7B0BC]">
                    <div className="flex items-center gap-1">
                      <User className="w-3 h-3 text-[#6B7585]" />
                      <span className="font-mono text-[11px] text-[#E4E8EE] truncate max-w-[80px]" title={op.initiatedBy || 'unknown'}>
                        {op.initiatedBy || 'System'}
                      </span>
                    </div>
                    {op.recoveryStatus && (
                      <span className="font-mono text-[10px] px-1 rounded bg-[#1A2129] border border-[#2A323C] text-[#C9A24A]">
                        {op.recoveryStatus}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Card Bottom: Failure Details (if present) */}
              {op.failureClassification && op.failureClassification !== 'NONE' && (
                <div className="p-3 rounded bg-[#C86A6A]/10 border border-[#C86A6A]/30 space-y-1.5 text-xs">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-1.5 text-[#C86A6A] font-semibold">
                      <ShieldAlert className="w-3.5 h-3.5" />
                      <span>Failure Classification: {op.failureClassification}</span>
                    </div>
                    <span className="inline-flex items-center gap-1 text-[10px] font-mono text-[#4FA88A] bg-[#141A20] px-1.5 py-0.5 rounded border border-[#2A323C]">
                      <ShieldCheck className="w-3 h-3 text-[#4FA88A]" />
                      <span>Host Path Redacted</span>
                    </span>
                  </div>

                  {op.failureDetails && (
                    <p className="font-mono text-[11px] text-[#E4E8EE] bg-[#0C1014] p-2 rounded border border-[#1F262E] break-words">
                      {op.failureDetails}
                    </p>
                  )}
                </div>
              )}

              {/* Dual-Custody Break-Glass Recovery Panel */}
              <BreakGlassPanel operation={op} onOperationReloadNeeded={fetchOperations} />
            </div>
          );
        })}
      </div>
    </div>
  );
};
