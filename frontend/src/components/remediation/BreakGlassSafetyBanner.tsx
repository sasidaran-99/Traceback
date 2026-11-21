import React from 'react';
import { ShieldAlert, Lock, Info } from 'lucide-react';

interface BreakGlassSafetyBannerProps {
  decisionApplied: boolean;
  gitRecoveryExecuted: boolean;
  refFrozen: boolean | null;
  operationStatus: string;
  message?: string;
}

export const BreakGlassSafetyBanner: React.FC<BreakGlassSafetyBannerProps> = ({
  decisionApplied,
  gitRecoveryExecuted,
  refFrozen,
  operationStatus,
  message,
}) => {
  // Format frozen state accurately: strictly preserve null as UNKNOWN, never convert null to false
  const getFrozenStateDisplay = (frozen: boolean | null) => {
    if (frozen === true) {
      return {
        text: 'YES (Confirmed Frozen)',
        color: 'text-[#7FA3C4]',
        bg: 'bg-[#3A6C96]/15',
        border: 'border-[#3A6C96]/30',
      };
    }
    if (frozen === false) {
      return {
        text: 'NO (Unfrozen)',
        color: 'text-[#C9A24A]',
        bg: 'bg-[#C9A24A]/15',
        border: 'border-[#C9A24A]/30',
      };
    }
    return {
      text: 'UNKNOWN (Unverified)',
      color: 'text-[#A7B0BC]',
      bg: 'bg-[#2A323C]/50',
      border: 'border-[#2A323C]',
    };
  };

  const frozenDisplay = getFrozenStateDisplay(refFrozen);

  return (
    <div
      className={`rounded-lg bg-[#0E1216] border ${
        decisionApplied ? 'border-[#3A6C96]/50' : 'border-[#C9A24A]/40'
      } p-4 space-y-3 font-sans text-xs`}
    >
      {/* Header Bar */}
      <div className="flex items-center justify-between pb-2.5 border-b border-[#1F262E]">
        <div className="flex items-center gap-2">
          {decisionApplied ? (
            <Lock className="w-4 h-4 text-[#7FA3C4]" />
          ) : (
            <ShieldAlert className="w-4 h-4 text-[#C9A24A]" />
          )}
          <span className="font-semibold text-[#E4E8EE]">
            {decisionApplied
              ? 'Authoritative Finalization Outcome'
              : 'Break-Glass State Notice'}
          </span>
        </div>

        <span className="px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#141A20] border border-[#2A323C] text-[#A7B0BC]">
          Decision Applied: {decisionApplied ? 'YES' : 'NO'}
        </span>
      </div>

      {/* Primary Affirmation Statement */}
      <div className="p-2.5 rounded bg-[#141A20] border border-[#1F262E] flex items-start gap-2.5 text-xs">
        <Info className="w-4 h-4 text-[#7FA3C4] shrink-0 mt-0.5" />
        <p className="text-[#E4E8EE] leading-relaxed font-medium">
          Break-glass decision recorded. The repository ref remains frozen. No Git recovery was performed.
        </p>
      </div>

      {/* Authoritative State Grid */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 pt-1 text-xs font-mono">
        {/* Decision */}
        <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
          <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-1">
            Decision
          </span>
          <span className="text-[#7FA3C4] font-semibold">KEEP FROZEN</span>
        </div>

        {/* Git Recovery Executed */}
        <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
          <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-1">
            Git Recovery Executed
          </span>
          <span className="text-[#C86A6A] font-semibold">
            {gitRecoveryExecuted ? 'YES' : 'NO'}
          </span>
        </div>

        {/* Git Mutation Performed */}
        <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
          <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-1">
            Git Mutation Performed
          </span>
          <span className="text-[#C86A6A] font-semibold">NO</span>
        </div>

        {/* Target Ref Remains Frozen */}
        <div className="p-2 rounded bg-[#0A0D11] border border-[#1F262E]">
          <span className="text-[10px] uppercase tracking-wider text-[#6B7585] block mb-1">
            Ref Remains Frozen
          </span>
          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${frozenDisplay.color} ${frozenDisplay.bg} border ${frozenDisplay.border}`}>
            {frozenDisplay.text}
          </span>
        </div>
      </div>

      {/* Operation Status & Backend Message */}
      <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-[#1F262E]/60 text-xs">
        <div className="flex items-center gap-1.5 font-mono">
          <span className="text-[#6B7585]">Operation Status:</span>
          <span className="text-[#C9A24A] font-medium">{operationStatus}</span>
        </div>

        {message && (
          <span className="text-[11px] text-[#A7B0BC] italic max-w-md truncate" title={message}>
            &ldquo;{message}&rdquo;
          </span>
        )}
      </div>
    </div>
  );
};
