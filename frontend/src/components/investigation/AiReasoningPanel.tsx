import React from 'react';
import { Sparkles, Brain, CheckCircle2, AlertTriangle, ShieldCheck, ListChecks, HelpCircle, FileText } from 'lucide-react';
import type { InvestigationAgentReport, InvestigationClaim } from '../../api/types';

interface AiReasoningPanelProps {
  report: InvestigationAgentReport;
  claims?: InvestigationClaim[];
  onSelectEvidence?: (evidenceId: string) => void;
}

export const AiReasoningPanel: React.FC<AiReasoningPanelProps> = ({
  report,
  claims = [],
  onSelectEvidence,
}) => {
  const isFallback = report.aiReasoningSummary?.includes('Deterministic fallback') || false;

  // Derive model / provider from metadata if available, avoiding hardcoding
  const modelProviderDisplay = isFallback
    ? 'Deterministic Fallback Mode (Bifrost Gateway unconfigured / offline)'
    : report.modelName
    ? `Bifrost Gateway / ${report.modelName}`
    : 'Bifrost AI Gateway / Runtime LLM Diagnostics';

  // Group claims by status for evidence traceability
  const supportedClaims = claims.filter((c) => c.status === 'SUPPORTED');
  const partialClaims = claims.filter((c) => c.status === 'PARTIALLY_SUPPORTED');
  const unsupportedOrContradictedClaims = claims.filter(
    (c) => c.status === 'UNSUPPORTED' || c.status === 'CONTRADICTED' || c.status === 'VALIDATION_ERROR'
  );

  return (
    <div className="rounded-xl border border-[#2A323C] bg-[#141A20] shadow-xl overflow-hidden space-y-6 p-6">
      {/* Header with Restrained Steel-Blue Accent */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-[#1F262E]">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-[#1A2129] border border-[#2A323C] flex items-center justify-center text-[#7FA3C4] shadow-xs">
            <Brain className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-mono font-bold tracking-wider uppercase text-[#7FA3C4]">
                AI-GENERATED DIAGNOSTIC SYNTHESIS
              </span>
              <span className="w-1.5 h-1.5 rounded-full bg-[#7FA3C4]" />
            </div>
            <p className="text-xs text-[#A7B0BC] mt-0.5 font-sans">
              Autonomous cognitive synthesis and diagnostic hypotheses formulated by the reasoning agent
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <span
            className={`text-xs font-mono px-3 py-1 rounded-md border ${
              isFallback
                ? 'bg-[#1A2129] text-[#C9A24A] border-[#2A323C]'
                : 'bg-[#1A2129] text-[#7FA3C4] border-[#2A323C]'
            }`}
          >
            {modelProviderDisplay}
          </span>
        </div>
      </div>

      {/* Mandatory Explanatory Grounding Note */}
      <div className="p-3.5 rounded-lg border border-[#2A323C] bg-[#1A2129] text-xs text-[#A7B0BC] flex items-start gap-3">
        <Sparkles className="w-4 h-4 text-[#7FA3C4] shrink-0 mt-0.5" />
        <div className="leading-relaxed font-sans text-xs">
          <strong className="font-semibold text-[#E4E8EE]">Epistemic Boundary Note:</strong> AI-generated analysis provides diagnostic interpretation. It does not independently change the deterministic investigation score or verdict.
        </div>
      </div>

      {/* Structured Sections */}
      <div className="space-y-5">
        {/* Section 1: ROOT CAUSE */}
        {report.primarySuspectedRootCause && (
          <div className="space-y-2">
            <h4 className="text-xs font-mono uppercase tracking-wider text-[#E4E8EE] font-bold flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-[#C9A24A]" />
              ROOT CAUSE
            </h4>
            <div className="p-4 rounded-lg bg-[#141A20] border-l-[3px] border-l-[#C9A24A] border-t border-r border-b border-[#1F262E] font-mono text-xs text-[#E4E8EE] leading-relaxed">
              Hypothesized Diagnostic Root Cause: {report.primarySuspectedRootCause}
            </div>
          </div>
        )}

        {/* Section 2: REASONING */}
        {report.aiReasoningSummary && (
          <div className="space-y-2">
            <h4 className="text-xs font-mono uppercase tracking-wider text-[#7FA3C4] font-bold flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-[#7FA3C4]" />
              REASONING &amp; DIAGNOSTIC SYNTHESIS
            </h4>
            <div className="p-4 rounded-lg bg-[#0C1014] border border-[#1F262E] font-mono text-xs text-[#E4E8EE] whitespace-pre-line leading-relaxed">
              {report.aiReasoningSummary}
            </div>
          </div>
        )}

        {/* Section 4: Grounded Claims Breakdown */}
        {claims.length > 0 && (
          <div className="space-y-2">
            <h4 className="text-xs font-mono uppercase tracking-wider text-[#6B7585] font-bold flex items-center gap-2">
              <ShieldCheck className="w-3.5 h-3.5 text-[#7FA3C4]" />
              REASONING CLAIMS &amp; EVIDENCE GROUNDING ({claims.length})
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {/* Supported Claims */}
              <div className="p-3.5 rounded-lg bg-[#1A2129] border border-[#1F262E] space-y-2">
                <div className="flex items-center justify-between text-xs font-mono">
                  <span className="text-[#4FA88A] font-semibold flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    Supported ({supportedClaims.length})
                  </span>
                </div>
                <ul className="space-y-1.5 text-[11px] font-sans text-[#A7B0BC]">
                  {supportedClaims.slice(0, 3).map((c) => (
                    <li key={c.claimId} className="truncate">
                      &bull; {c.statement}
                    </li>
                  ))}
                  {supportedClaims.length > 3 && (
                    <li className="text-[#6B7585] font-mono text-[10px]">
                      +{supportedClaims.length - 3} more supported claims
                    </li>
                  )}
                  {supportedClaims.length === 0 && (
                    <li className="text-[#6B7585] italic">No claims verified as supported</li>
                  )}
                </ul>
              </div>

              {/* Partially Supported Claims */}
              <div className="p-3.5 rounded-lg bg-[#1A2129] border border-[#1F262E] space-y-2">
                <div className="flex items-center justify-between text-xs font-mono">
                  <span className="text-[#C9A24A] font-semibold flex items-center gap-1.5">
                    <AlertTriangle className="w-3.5 h-3.5" />
                    Partially Supported ({partialClaims.length})
                  </span>
                </div>
                <ul className="space-y-1.5 text-[11px] font-sans text-[#A7B0BC]">
                  {partialClaims.slice(0, 3).map((c) => (
                    <li key={c.claimId} className="truncate">
                      &bull; {c.statement}
                    </li>
                  ))}
                  {partialClaims.length === 0 && (
                    <li className="text-[#6B7585] italic">No partially supported claims</li>
                  )}
                </ul>
              </div>

              {/* Unsupported / Contradicted Claims */}
              <div className="p-3.5 rounded-lg bg-[#1A2129] border border-[#1F262E] space-y-2">
                <div className="flex items-center justify-between text-xs font-mono">
                  <span className="text-[#7C8796] font-semibold flex items-center gap-1.5">
                    <HelpCircle className="w-3.5 h-3.5" />
                    Unsupported / Contradicted ({unsupportedOrContradictedClaims.length})
                  </span>
                </div>
                <ul className="space-y-1.5 text-[11px] font-sans text-[#A7B0BC]">
                  {unsupportedOrContradictedClaims.slice(0, 3).map((c) => (
                    <li key={c.claimId} className="truncate">
                      &bull; {c.statement}
                    </li>
                  ))}
                  {unsupportedOrContradictedClaims.length === 0 && (
                    <li className="text-[#6B7585] italic">No ungrounded or contradicted claims</li>
                  )}
                </ul>
              </div>
            </div>
          </div>
        )}

        {/* Section 5: VERIFICATION STEPS & UNCERTAINTIES Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* VERIFICATION STEPS */}
          <div className="rounded-lg border border-[#1F262E] bg-[#141A20] p-4 space-y-3">
            <h4 className="text-xs font-mono uppercase tracking-wider text-[#4FA88A] font-bold flex items-center gap-2">
              <ListChecks className="w-4 h-4" />
              VERIFICATION STEPS
            </h4>
            {report.recommendedVerificationSteps && report.recommendedVerificationSteps.length > 0 ? (
              <ul className="space-y-2 text-xs font-sans text-[#E4E8EE]">
                {report.recommendedVerificationSteps.map((step, idx) => (
                  <li
                    key={idx}
                    className="flex items-start gap-2.5 bg-[#1A2129] p-2.5 rounded-md border border-[#1F262E]"
                  >
                    <span className="w-5 h-5 rounded bg-[#141A20] border border-[#2A323C] text-[#4FA88A] font-mono text-[11px] font-bold flex items-center justify-center shrink-0 mt-0.5">
                      {idx + 1}
                    </span>
                    <span className="leading-relaxed">{step}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-[#6B7585] italic">No verification steps specified.</p>
            )}
          </div>

          {/* UNCERTAINTIES */}
          <div className="rounded-lg border border-[#1F262E] bg-[#141A20] p-4 space-y-3">
            <h4 className="text-xs font-mono uppercase tracking-wider text-[#C9A24A] font-bold flex items-center gap-2">
              <HelpCircle className="w-4 h-4" />
              UNCERTAINTIES
            </h4>
            {report.remainingUncertainties && report.remainingUncertainties.length > 0 ? (
              <ul className="space-y-2 text-xs font-sans text-[#E4E8EE]">
                {report.remainingUncertainties.map((unc, idx) => (
                  <li
                    key={idx}
                    className="flex items-start gap-2.5 bg-[#1A2129] p-2.5 rounded-md border border-[#1F262E]"
                  >
                    <span className="w-5 h-5 rounded bg-[#141A20] border border-[#2A323C] text-[#C9A24A] font-mono text-[11px] font-bold flex items-center justify-center shrink-0 mt-0.5">
                      ?
                    </span>
                    <span className="leading-relaxed">{unc}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-[#6B7585] italic">No remaining uncertainties noted.</p>
            )}
          </div>
        </div>

        {/* Section 6: Registered Evidence Citations */}
        {report.supportingEvidenceIds && report.supportingEvidenceIds.length > 0 && (
          <div className="pt-2 border-t border-[#1F262E] flex flex-wrap items-center gap-2 text-xs font-mono">
            <span className="text-[#6B7585] flex items-center gap-1.5">
              <FileText className="w-3.5 h-3.5" />
              Cited Evidence IDs:
            </span>
            {report.supportingEvidenceIds.map((evId) => (
              <button
                key={evId}
                type="button"
                onClick={() => onSelectEvidence?.(evId)}
                className="px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C] text-[#7FA3C4] hover:text-[#E4E8EE] hover:border-[#7FA3C4] transition-colors text-[11px]"
                title={`Jump to evidence ${evId}`}
              >
                {evId}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
