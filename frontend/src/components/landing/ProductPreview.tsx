import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldCheck,
  Sparkles,
  ExternalLink,
  Copy,
  Check,
} from 'lucide-react';
import { VerdictBadge } from '../shared/VerdictBadge';
import { ConfidenceMeter } from '../shared/ConfidenceMeter';

export const ProductPreview: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'overview' | 'hypotheses' | 'claims' | 'evidence'>('overview');
  const [copied, setCopied] = useState(false);

  const sampleId = '5f0cd287-4f2d-4f26-b43b-f46a7e7aa78c';

  const handleCopy = () => {
    navigator.clipboard.writeText(sampleId);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-2xl border border-[#2A323C] bg-[#141A20] shadow-2xl overflow-hidden font-sans">
      {/* Dossier Header Preview Bar */}
      <div className="px-5 py-3.5 bg-[#0E1216] border-b border-[#1F262E] flex flex-wrap items-center justify-between gap-3 select-none">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 font-mono text-xs text-[#A7B0BC]">
            <span className="text-[#6B7585]">Dossier:</span>
            <span className="text-[#E4E8EE] font-semibold">{sampleId.substring(0, 8)}...</span>
            <button
              type="button"
              onClick={handleCopy}
              className="p-1 rounded hover:bg-[#1A2129] text-[#6B7585] hover:text-[#E4E8EE] transition-colors"
              title="Copy ID"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-[#4FA88A]" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          </div>
          <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
            scenario-a-live-activation
          </span>
          <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-[#1A2129] text-[#A7B0BC] border border-[#2A323C]">
            isolated-test
          </span>
        </div>

        <div className="flex items-center gap-3 font-mono text-xs">
          <VerdictBadge verdict="CONFIRMED" size="sm" />
          <Link
            to="/dashboard"
            className="inline-flex items-center gap-1 text-[11px] text-[#7FA3C4] hover:underline"
          >
            <span>Open in Workspace</span>
            <ExternalLink className="w-3 h-3" />
          </Link>
        </div>
      </div>

      {/* Internal Tabs Bar */}
      <div className="px-5 pt-3 bg-[#0C1014] border-b border-[#1F262E] flex items-center gap-2 overflow-x-auto text-xs font-mono select-none">
        <button
          type="button"
          onClick={() => setActiveTab('overview')}
          className={`px-3 py-2 border-b-2 font-medium transition-colors ${
            activeTab === 'overview'
              ? 'border-[#7FA3C4] text-[#E4E8EE] font-semibold'
              : 'border-transparent text-[#6B7585] hover:text-[#A7B0BC]'
          }`}
        >
          AI Report &amp; Overview
        </button>
        <button
          type="button"
          onClick={() => setActiveTab('hypotheses')}
          className={`px-3 py-2 border-b-2 font-medium transition-colors ${
            activeTab === 'hypotheses'
              ? 'border-[#7FA3C4] text-[#E4E8EE] font-semibold'
              : 'border-transparent text-[#6B7585] hover:text-[#A7B0BC]'
          }`}
        >
          Hypotheses (2)
        </button>
        <button
          type="button"
          onClick={() => setActiveTab('claims')}
          className={`px-3 py-2 border-b-2 font-medium transition-colors ${
            activeTab === 'claims'
              ? 'border-[#7FA3C4] text-[#E4E8EE] font-semibold'
              : 'border-transparent text-[#6B7585] hover:text-[#A7B0BC]'
          }`}
        >
          Claims Grounding
        </button>
        <button
          type="button"
          onClick={() => setActiveTab('evidence')}
          className={`px-3 py-2 border-b-2 font-medium transition-colors ${
            activeTab === 'evidence'
              ? 'border-[#7FA3C4] text-[#E4E8EE] font-semibold'
              : 'border-transparent text-[#6B7585] hover:text-[#A7B0BC]'
          }`}
        >
          Evidence Explorer (5)
        </button>
      </div>

      {/* Tab Panel Body */}
      <div className="p-6 space-y-5 bg-[#0C1014]/60">
        {activeTab === 'overview' && (
          <>
            {/* Top Summary Metrics */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="p-4 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-1">
                <span className="text-[11px] font-mono text-[#6B7585] uppercase tracking-wider block">
                  Authoritative Verdict
                </span>
                <div className="text-sm font-bold text-[#4FA88A] flex items-center gap-2">
                  <ShieldCheck className="w-4 h-4" />
                  <span>CONFIRMED ROOT CAUSE</span>
                </div>
                <p className="text-[11px] text-[#A7B0BC]">
                  Corroborated by telemetry coordinates &amp; commit diff
                </p>
              </div>

              <div className="p-4 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-1">
                <span className="text-[11px] font-mono text-[#6B7585] uppercase tracking-wider block">
                  Investigation Confidence
                </span>
                <div className="pt-0.5">
                  <ConfidenceMeter score={0.95} showLabel={true} />
                </div>
                <p className="text-[11px] text-[#6B7585] font-mono">
                  Dual: 95.0% Grounded / Raw: 85.0 pts
                </p>
              </div>

              <div className="p-4 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-1">
                <span className="text-[11px] font-mono text-[#6B7585] uppercase tracking-wider block">
                  Scoring Model ($S$)
                </span>
                <div className="text-xs font-mono font-bold text-[#E4E8EE]">
                  S = T + O + A - C
                </div>
                <p className="text-[11px] text-[#A7B0BC] font-mono">
                  T:+30 | O:+30 | A:+25 | C:0
                </p>
              </div>
            </div>

            {/* AI Reasoning Synthesis Panel Preview (Restrained Steel-Blue / Neutral Graphite) */}
            <div className="p-5 rounded-xl border border-[#2A323C] bg-[#141A20] space-y-3 shadow-md">
              <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#1F262E]">
                <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#7FA3C4]">
                  <Sparkles className="w-4 h-4" />
                  <span>AI-GENERATED DIAGNOSTIC SYNTHESIS</span>
                </div>
                <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
                  gemini-3.8-flash via Bifrost Gateway
                </span>
              </div>

              <div className="p-3 rounded-lg bg-[#0C1014] border border-[#1F262E] text-xs font-mono text-[#6B7585] leading-relaxed">
                Epistemic Grounding Enforced: Generative reasoning assists engineer triage and verification planning. It does not alter the deterministic hypothesis rank or scoring outcome.
              </div>

              <div className="space-y-1 text-xs">
                <span className="text-[11px] font-mono uppercase font-bold text-[#A7B0BC] tracking-wider block">
                  Identified Root Cause Narrative
                </span>
                <p className="text-xs text-[#E4E8EE] leading-relaxed font-sans">
                  The primary cause of the failure is a <code className="text-[#C86A6A] font-mono">java.lang.NullPointerException</code> triggered when calling <code className="text-[#7FA3C4] font-mono">customer.getId()</code> in <code className="text-[#E4E8EE] font-mono font-semibold">OrderProcessingService.processOrder</code> at line 42. Recent commit <code className="text-[#7FA3C4] font-mono font-bold">c1a89b3</code> removed null validation logic immediately preceding the invocation.
                </p>
              </div>

              <div className="pt-2 flex flex-wrap items-center gap-2 text-xs font-mono">
                <span className="text-[#6B7585]">Linked Evidence:</span>
                <span className="px-2 py-0.5 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
                  EVD-GIT-fdfe77f
                </span>
                <span className="px-2 py-0.5 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
                  EVD-STACK-ROOT
                </span>
              </div>
            </div>
          </>
        )}

        {activeTab === 'hypotheses' && (
          <div className="space-y-3 font-mono text-xs">
            <div className="p-4 rounded-xl border border-[#2A323C] bg-[#141A20] space-y-2">
              <div className="flex items-center justify-between">
                <span className="font-bold text-[#E4E8EE] flex items-center gap-2">
                  <span className="w-5 h-5 rounded bg-[#1A2129] border border-[#2A323C] text-[#7FA3C4] flex items-center justify-center font-bold text-[10px]">
                    #1
                  </span>
                  Code regression in commit c1a89b3
                </span>
                <VerdictBadge verdict="CONFIRMED" size="sm" />
              </div>
              <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
                OrderProcessingService.java was modified 12m before the incident window, touching the exact method and stack frame line 42 where NullPointerException occurred.
              </p>
              <div className="flex items-center gap-2 text-[11px] text-[#6B7585]">
                <span>Score: 85.0 pts</span> &bull; <span>Structural Overlap: YES</span> &bull; <span>Config Delta: NO</span>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'claims' && (
          <div className="p-4 rounded-xl border border-[#1F262E] bg-[#141A20] font-mono text-xs space-y-3">
            <div className="flex items-center justify-between pb-2 border-b border-[#1F262E]">
              <span className="text-[#E4E8EE] font-semibold">&ldquo;Null customer passed into OrderProcessingService.processOrder&rdquo;</span>
              <span className="px-2 py-0.5 rounded bg-[#4FA88A]/10 text-[#4FA88A] border border-[#4FA88A]/30 text-[10px]">
                SUPPORTED
              </span>
            </div>
            <p className="text-xs text-[#A7B0BC] font-sans">
              Grounding: Verified against stack trace frame at line 42 and git commit hunk in c1a89b3.
            </p>
          </div>
        )}

        {activeTab === 'evidence' && (
          <div className="space-y-2 font-mono text-xs">
            <div className="p-3 rounded-lg border border-[#1F262E] bg-[#141A20] flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="px-2 py-0.5 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
                  EVD-GIT-fdfe77f
                </span>
                <span className="text-[#E4E8EE]">Commit c1a89b3: Refactor customer validation</span>
              </div>
              <span className="text-[11px] text-[#6B7585]">
                SHA-256: d23bba4c443e...
              </span>
            </div>
          </div>
        )}

        {/* Foundational Engineering Motto */}
        <div className="pt-3 border-t border-[#1F262E] text-center text-xs font-mono text-[#6B7585]">
          &ldquo;Don&apos;t just tell developers that something broke &mdash; prove where, when, and why it occurred with deterministic code-level correlation.&rdquo;
        </div>
      </div>
    </div>
  );
};
