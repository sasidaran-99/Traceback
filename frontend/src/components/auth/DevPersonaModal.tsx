import React from 'react';
import {
  X,
  User,
  Check,
  AlertTriangle,
  LogOut,
  Clock,
  KeyRound,
  Lock,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import type { DevPersona } from '../../api/types';

interface PersonaMetadata {
  id: DevPersona;
  name: string;
  subject: string;
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER';
  roleColor: string;
  badgeBg: string;
  description: string;
  capabilities: string[];
}

const PERSONAS: PersonaMetadata[] = [
  {
    id: 'ALICE_ADMIN',
    name: 'Alice Admin',
    subject: 'alice-admin',
    role: 'ADMIN',
    roleColor: 'text-[#C9A24A]',
    badgeBg: 'bg-[#C9A24A]/10 border-[#C9A24A]/30',
    description: 'Lead remediation administrator with full dual-custody proposal, ref freeze, and finalization rights.',
    capabilities: ['Propose break-glass requests', 'Freeze / unfreeze Git refs', 'Finalize approved executions'],
  },
  {
    id: 'BOB_ADMIN',
    name: 'Bob Admin',
    subject: 'bob-admin',
    role: 'ADMIN',
    roleColor: 'text-[#C9A24A]',
    badgeBg: 'bg-[#C9A24A]/10 border-[#C9A24A]/30',
    description: 'Independent administrative peer. Serves as second custodian for dual-custody break-glass approval.',
    capabilities: ['Approve / reject Alice’s proposals', 'Independent dual-custody audit', 'Ref inspection'],
  },
  {
    id: 'CHARLIE_OPERATOR',
    name: 'Charlie Operator',
    subject: 'charlie-operator',
    role: 'OPERATOR',
    roleColor: 'text-[#7FA3C4]',
    badgeBg: 'bg-[#3A6C96]/20 border-[#3A6C96]/40',
    description: 'Standard remediation operator. Proposes safe automated fixes and monitors execution progress.',
    capabilities: ['Propose standard remediations', 'Discover investigation operations', 'Read telemetry'],
  },
  {
    id: 'DANA_VIEWER',
    name: 'Dana Viewer',
    subject: 'dana-viewer',
    role: 'VIEWER',
    roleColor: 'text-[#A7B0BC]',
    badgeBg: 'bg-[#2A323C]/40 border-[#2A323C]',
    description: 'Forensic auditor with read-only access. Audits dossiers, evidence, and sanitized remediation journals.',
    capabilities: ['View investigations and dossiers', 'Discover remediations (read-only)', 'Audit trail inspection'],
  },
];

export const DevPersonaModal: React.FC = () => {
  const {
    user,
    isAuthenticated,
    isLoading,
    error,
    login,
    logout,
    clearError,
    isPersonaModalOpen,
    closePersonaModal,
  } = useAuth();

  if (!isPersonaModalOpen) {
    return null;
  }

  const handleSelectPersona = async (persona: DevPersona) => {
    try {
      await login(persona);
    } catch {
      // Error handled in AuthContext
    }
  };

  const remainingSeconds = user?.expiresAt
    ? Math.max(0, user.expiresAt - Math.floor(Date.now() / 1000))
    : 0;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs font-sans">
      <div
        className="w-full max-w-2xl bg-[#0E1216] border border-[#2A323C] rounded-lg shadow-2xl flex flex-col overflow-hidden max-h-[90vh]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dev-auth-modal-title"
      >
        {/* Header */}
        <div className="px-6 py-4 border-b border-[#1F262E] flex items-center justify-between bg-[#141A20]/80">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded bg-[#1A2129] border border-[#2A323C] text-[#7FA3C4]">
              <KeyRound className="w-4 h-4" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 id="dev-auth-modal-title" className="text-sm font-semibold text-[#E4E8EE]">
                  Development Persona Switcher
                </h2>
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#C9A24A]/10 border border-[#C9A24A]/30 text-[#C9A24A] font-semibold">
                  DEV-ONLY
                </span>
              </div>
              <p className="text-xs text-[#6B7585] mt-0.5">
                Authenticates against <code className="font-mono text-[#A7B0BC]">POST /api/dev/auth/token</code> with genuine RS256 JWTs.
              </p>
            </div>
          </div>
          <button
            onClick={closePersonaModal}
            className="p-1.5 rounded text-[#6B7585] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors focus:outline-none"
            aria-label="Close modal"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Error Alert */}
        {error && (
          <div className="mx-6 mt-4 p-3 rounded-md bg-[#C86A6A]/10 border border-[#C86A6A]/30 flex items-start gap-2.5 text-xs text-[#E4E8EE]">
            <AlertTriangle className="w-4 h-4 text-[#C86A6A] shrink-0 mt-0.5" />
            <div className="flex-1">
              <span className="font-semibold text-[#C86A6A]">Authentication Error: </span>
              {error}
            </div>
            <button
              onClick={clearError}
              className="text-[#6B7585] hover:text-[#E4E8EE] text-[11px] underline"
            >
              Dismiss
            </button>
          </div>
        )}

        {/* Active Session Strip */}
        {isAuthenticated && user && (
          <div className="mx-6 mt-4 p-3 rounded-md bg-[#141A20] border border-[#1F262E] flex flex-wrap items-center justify-between gap-3 text-xs">
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2">
                <span className="text-[#6B7585]">Active Subject:</span>
                <span className="font-mono font-semibold text-[#E4E8EE]">{user.subject}</span>
              </div>
              <div className="flex items-center gap-1.5">
                {user.roles.map((r) => (
                  <span
                    key={r}
                    className="font-mono text-[10px] px-1.5 py-0.2 rounded border bg-[#1A2129] border-[#2A323C] text-[#7FA3C4]"
                  >
                    ROLE_{r}
                  </span>
                ))}
              </div>
              <div className="flex items-center gap-1 text-[#6B7585] text-[11px] font-mono">
                <Clock className="w-3 h-3" />
                <span>{Math.floor(remainingSeconds / 60)}m {remainingSeconds % 60}s</span>
              </div>
            </div>

            <button
              onClick={logout}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-[#1A2129] hover:bg-[#2A323C] border border-[#2A323C] text-[#C86A6A] hover:text-white transition-colors text-xs"
            >
              <LogOut className="w-3.5 h-3.5" />
              <span>Sign Out</span>
            </button>
          </div>
        )}

        {/* Persona Selection Grid */}
        <div className="p-6 overflow-y-auto space-y-3 flex-1">
          <div className="text-[11px] uppercase tracking-wider font-semibold text-[#6B7585] mb-2">
            Select Active Persona
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {PERSONAS.map((persona) => {
              const isActive = user?.subject === persona.subject;
              return (
                <div
                  key={persona.id}
                  className={`p-3.5 rounded-lg border transition-all flex flex-col justify-between ${
                    isActive
                      ? 'bg-[#1A2129] border-[#7FA3C4] shadow-xs'
                      : 'bg-[#141A20] border-[#1F262E] hover:border-[#2A323C] hover:bg-[#1A2129]/60'
                  }`}
                >
                  <div>
                    <div className="flex items-center justify-between mb-1.5">
                      <div className="flex items-center gap-2">
                        <User className="w-4 h-4 text-[#7FA3C4]" />
                        <span className="font-semibold text-xs text-[#E4E8EE]">{persona.name}</span>
                      </div>
                      <span
                        className={`text-[10px] font-mono px-1.5 py-0.5 rounded border font-semibold ${persona.badgeBg} ${persona.roleColor}`}
                      >
                        {persona.role}
                      </span>
                    </div>

                    <div className="font-mono text-[11px] text-[#6B7585] mb-2">
                      sub: {persona.subject}
                    </div>

                    <p className="text-xs text-[#A7B0BC] leading-relaxed mb-3">
                      {persona.description}
                    </p>

                    <div className="space-y-1 mb-3">
                      {persona.capabilities.map((cap, i) => (
                        <div key={i} className="flex items-center gap-1.5 text-[11px] text-[#6B7585]">
                          <span className="w-1 h-1 rounded-full bg-[#7FA3C4]" />
                          <span>{cap}</span>
                        </div>
                      ))}
                    </div>
                  </div>

                  <button
                    onClick={() => handleSelectPersona(persona.id)}
                    disabled={isLoading || isActive}
                    className={`w-full py-1.5 px-3 rounded text-xs font-medium flex items-center justify-center gap-1.5 transition-colors ${
                      isActive
                        ? 'bg-[#4FA88A]/10 border border-[#4FA88A]/30 text-[#4FA88A] cursor-default'
                        : 'bg-[#3A6C96] hover:bg-[#457CAB] text-white shadow-xs'
                    }`}
                  >
                    {isActive ? (
                      <>
                        <Check className="w-3.5 h-3.5" />
                        <span>Active Identity</span>
                      </>
                    ) : (
                      <>
                        <span>Assume Persona</span>
                      </>
                    )}
                  </button>
                </div>
              );
            })}
          </div>
        </div>

        {/* Footer Note */}
        <div className="px-6 py-3 border-t border-[#1F262E] bg-[#141A20]/60 flex items-center justify-between text-[11px] text-[#6B7585]">
          <div className="flex items-center gap-1.5">
            <Lock className="w-3.5 h-3.5 text-[#4FA88A]" />
            <span>Zero frontend signing. JWTs minted by backend ephemeral RS256 keypair.</span>
          </div>
          <button
            onClick={closePersonaModal}
            className="text-xs text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
