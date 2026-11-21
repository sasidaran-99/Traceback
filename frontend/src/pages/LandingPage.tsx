import React from 'react';
import { Link } from 'react-router-dom';
import {
  Play,
  LayoutDashboard,
  GitBranch,
  Terminal,
  Layers,
  ArrowRight,
  Sparkles,
  CheckCircle2,
  FileCode,
  Sliders,
} from 'lucide-react';
import { LandingNavbar } from '../components/landing/LandingNavbar';
import { WorkflowVisualization } from '../components/landing/WorkflowVisualization';
import { CapabilityCard } from '../components/landing/CapabilityCard';
import { ProductPreview } from '../components/landing/ProductPreview';

export const LandingPage: React.FC = () => {
  return (
    <div className="min-h-screen bg-[#0C1014] text-[#E4E8EE] font-sans selection:bg-[#7FA3C4]/20 selection:text-[#E4E8EE]">
      {/* Section 1: Navigation Bar */}
      <LandingNavbar />

      {/* Section 2: Hero Section (Tightened vertical spacing for above-the-fold visibility) */}
      <section className="relative px-6 md:px-12 lg:px-20 pt-8 pb-12 md:pt-10 md:pb-14 border-b border-[#1F262E] overflow-hidden">
        <div className="max-w-7xl mx-auto grid grid-cols-1 lg:grid-cols-12 gap-8 lg:gap-12 items-center relative z-10">
          {/* Left Column: Headline & Action */}
          <div className="lg:col-span-6 space-y-5">
            {/* Tag / Category Badge with TraceBack Brand Mark */}
            <div className="flex items-center gap-3">
              <img
                src="/assets/traceback-logo.png"
                alt="TraceBack Logo"
                className="h-8 w-auto object-contain shrink-0"
              />
              <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-[#2A323C] bg-[#1A2129] text-xs font-mono text-[#7FA3C4]">
                <Sparkles className="w-3.5 h-3.5" />
                <span className="font-semibold uppercase tracking-wider text-[11px]">
                  Deterministic Root Cause Analysis
                </span>
              </div>
            </div>

            {/* Primary Headline */}
            <h1 className="text-3xl sm:text-4xl lg:text-5xl font-mono font-bold tracking-tight text-[#E4E8EE] leading-tight">
              Understand why your software failed.
            </h1>

            {/* Shortened Supporting Description */}
            <p className="text-base text-[#B0B8C4] leading-relaxed font-sans max-w-xl">
              TraceBack connects stack traces, code changes, and runtime evidence to autonomously prove software failure root causes.
            </p>

            {/* CTA Action Buttons: Start Investigation Primary, Open Workspace Secondary */}
            <div className="flex flex-wrap items-center gap-3.5 pt-1">
              <Link
                to="/new"
                className="inline-flex items-center gap-2 px-5 py-2.5 rounded-lg font-sans text-xs sm:text-sm font-semibold bg-[#3A6C96] text-[#FFFFFF] hover:bg-[#457CAB] transition-colors shadow-sm cursor-pointer"
              >
                <Play className="w-4 h-4 fill-current" />
                <span>Start an Investigation</span>
              </Link>

              <Link
                to="/dashboard"
                className="inline-flex items-center gap-2 px-5 py-2.5 rounded-lg font-sans text-xs sm:text-sm font-semibold bg-[#141A20] text-[#E4E8EE] hover:bg-[#1A2129] border border-[#2A323C] hover:border-[#7FA3C4]/50 transition-all cursor-pointer"
                title="Open Workspace / Explore Dashboard"
              >
                <LayoutDashboard className="w-4 h-4 text-[#7FA3C4]" />
                <span>Open Workspace</span>
                <span className="sr-only">Explore Dashboard</span>
              </Link>
            </div>

            {/* Core Mathematical Grounding Breakdown: S = T + O + A - C */}
            <div className="pt-4 border-t border-[#1F262E] space-y-2 text-xs font-mono">
              <div className="text-[11px] font-semibold text-[#7FA3C4] flex items-center gap-1.5">
                <span>Deterministic Scoring Model:</span>
                <span className="text-[#E4E8EE] font-bold">S = T + O + A − C</span>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px] text-[#828D9E]">
                <div className="p-2 rounded bg-[#141A20] border border-[#1F262E]">
                  <strong className="text-[#E4E8EE] block">T: Temporal</strong>
                  <span>Pre-incident timing</span>
                </div>
                <div className="p-2 rounded bg-[#141A20] border border-[#1F262E]">
                  <strong className="text-[#4FA88A] block">O: Overlap</strong>
                  <span>Line &amp; frame matches</span>
                </div>
                <div className="p-2 rounded bg-[#141A20] border border-[#1F262E]">
                  <strong className="text-[#7FA3C4] block">A: Agreement</strong>
                  <span>Multi-signal signals</span>
                </div>
                <div className="p-2 rounded bg-[#141A20] border border-[#1F262E]">
                  <strong className="text-[#C86A6A] block">−C: Contradiction</strong>
                  <span>Penalties applied</span>
                </div>
              </div>
            </div>
          </div>

          {/* Right Column: Workflow Visualization */}
          <div className="lg:col-span-6">
            <WorkflowVisualization />
          </div>
        </div>
      </section>

      {/* Section 3: How TraceBack Works */}
      <section id="how-it-works" className="px-6 md:px-12 lg:px-20 py-24 border-b border-[#1F262E] bg-[#0E1216]/60">
        <div className="max-w-7xl mx-auto space-y-12">
          {/* Section Header */}
          <div className="text-center max-w-2xl mx-auto space-y-3">
            <span className="text-xs font-mono text-[#7FA3C4] uppercase tracking-wider font-semibold">
              The Forensic Investigation Pipeline
            </span>
            <h2 className="text-3xl font-mono font-bold text-[#E4E8EE]">
              How TraceBack Works
            </h2>
            <p className="text-sm text-[#A7B0BC] leading-relaxed font-sans">
              A structured 5-step methodology that converts crash dumps into reproducible, evidence-grounded diagnosis.
            </p>
          </div>

          {/* 5-Step Process Grid */}
          <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
            {/* Step 1 */}
            <div className="p-5 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-3 relative flex flex-col justify-between hover:border-[#2A323C] transition-colors">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-bold text-[#7FA3C4] px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
                    01
                  </span>
                  <Terminal className="w-4 h-4 text-[#A7B0BC]" />
                </div>
                <h3 className="text-sm font-mono font-bold text-[#E4E8EE]">
                  Capture the Failure
                </h3>
                <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
                  Provide an exception stack trace, crash log snippet, or target a repository to detect working-tree anomalies.
                </p>
              </div>
              <div className="text-[10px] font-mono text-[#6B7585] pt-2 border-t border-[#1F262E]">
                Input Ingestion
              </div>
            </div>

            {/* Step 2 */}
            <div className="p-5 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-3 relative flex flex-col justify-between hover:border-[#2A323C] transition-colors">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-bold text-[#7FA3C4] px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
                    02
                  </span>
                  <GitBranch className="w-4 h-4 text-[#A7B0BC]" />
                </div>
                <h3 className="text-sm font-mono font-bold text-[#E4E8EE]">
                  Analyze Code Changes
                </h3>
                <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
                  Trace commits, configuration deltas, and file modifications across the pre-incident window.
                </p>
              </div>
              <div className="text-[10px] font-mono text-[#6B7585] pt-2 border-t border-[#1F262E]">
                Git &amp; Config Diffing
              </div>
            </div>

            {/* Step 3 */}
            <div className="p-5 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-3 relative flex flex-col justify-between hover:border-[#2A323C] transition-colors">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-bold text-[#7FA3C4] px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
                    03
                  </span>
                  <Sliders className="w-4 h-4 text-[#A7B0BC]" />
                </div>
                <h3 className="text-sm font-mono font-bold text-[#E4E8EE]">
                  Correlate Evidence
                </h3>
                <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
                  Calculate temporal proximity ($T$), stack frame code overlap ($O$), and multi-signal agreement ($A$).
                </p>
              </div>
              <div className="text-[10px] font-mono text-[#6B7585] pt-2 border-t border-[#1F262E]">
                Deterministic Scoring
              </div>
            </div>

            {/* Step 4 */}
            <div className="p-5 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-3 relative flex flex-col justify-between hover:border-[#2A323C] transition-colors">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-bold text-[#7FA3C4] px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
                    04
                  </span>
                  <Sparkles className="w-4 h-4 text-[#7FA3C4]" />
                </div>
                <h3 className="text-sm font-mono font-bold text-[#E4E8EE]">
                  AI-Assisted Reasoning
                </h3>
                <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
                  Generate root-cause hypotheses, verification steps, and remaining uncertainties via Gemini Flash.
                </p>
              </div>
              <div className="text-[10px] font-mono text-[#7FA3C4] pt-2 border-t border-[#1F262E]">
                Cognitive Synthesis
              </div>
            </div>

            {/* Step 5 */}
            <div className="p-5 rounded-xl border border-[#1F262E] bg-[#141A20] space-y-3 relative flex flex-col justify-between hover:border-[#2A323C] transition-colors">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-bold text-[#7FA3C4] px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
                    05
                  </span>
                  <CheckCircle2 className="w-4 h-4 text-[#4FA88A]" />
                </div>
                <h3 className="text-sm font-mono font-bold text-[#E4E8EE]">
                  Determine the Verdict
                </h3>
                <p className="text-xs text-[#A7B0BC] font-sans leading-relaxed">
                  Deliver an authoritative verdict (CONFIRMED, PROBABLE) grounded strictly in verifiable evidence.
                </p>
              </div>
              <div className="text-[10px] font-mono text-[#4FA88A] pt-2 border-t border-[#1F262E]">
                Authoritative Report
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Section 4: Core Capabilities */}
      <section id="capabilities" className="px-6 md:px-12 lg:px-20 py-24 border-b border-[#1F262E]">
        <div className="max-w-7xl mx-auto space-y-12">
          {/* Section Header */}
          <div className="text-center max-w-2xl mx-auto space-y-3">
            <span className="text-xs font-mono text-[#7FA3C4] uppercase tracking-wider font-semibold">
              Engineered for Production Reliability
            </span>
            <h2 className="text-3xl font-mono font-bold text-[#E4E8EE]">
              Core Capabilities
            </h2>
            <p className="text-sm text-[#A7B0BC] leading-relaxed font-sans">
              Precision tools designed to eliminate guesswork from post-incident analysis and software regression triage.
            </p>
          </div>

          {/* 6 Capability Cards Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            <CapabilityCard
              icon={Terminal}
              title="Stack Trace Analysis"
              description="Parses Java, Node, and Python exception traces into exact class, method, and line coordinate evidence without regex fragility."
              tag="Telemetry"
            />

            <CapabilityCard
              icon={GitBranch}
              title="Git Commit Correlation"
              description="Correlates commits in the incident window directly against failing stack frames to pinpoint regression culprits."
              tag="Repository"
            />

            <CapabilityCard
              icon={FileCode}
              title="Evidence-Based Investigation"
              description="Every commit, stack frame, and configuration diff is registered with a cryptographic SHA-256 payload checksum."
              tag="Integrity"
            />

            <CapabilityCard
              icon={Sparkles}
              title="AI-Assisted Root Cause Analysis"
              description="Leverages Gemini 3.8 Flash through Bifrost Gateway for cognitive synthesis, claim verification, and diagnostic steps."
              tag="Intelligence"
            />

            <CapabilityCard
              icon={Sliders}
              title="Deterministic Scoring"
              description="Hypotheses are ranked by mathematical formula S = T + O + A - C, guaranteeing zero AI hallucinations alter scores."
              tag="Algorithm"
            />

            <CapabilityCard
              icon={Layers}
              title="Investigation Reports"
              description="Produces complete forensic dossiers including chronological timelines, claim tables, and verifiable verification steps."
              tag="Reporting"
            />
          </div>
        </div>
      </section>

      {/* Section 5: Product Preview */}
      <section id="preview" className="px-6 md:px-12 lg:px-20 py-24 border-b border-[#1F262E] bg-[#0E1216]/60">
        <div className="max-w-7xl mx-auto space-y-8">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div className="space-y-2">
              <span className="text-xs font-mono text-[#7FA3C4] uppercase tracking-wider font-semibold">
                Forensic Workbench
              </span>
              <h2 className="text-3xl font-mono font-bold text-[#E4E8EE]">
                Real-World Investigation Preview
              </h2>
              <p className="text-sm text-[#A7B0BC] font-sans">
                Explore an actual dossier: interactive evidence explorer, claims grounding, and AI diagnostic narrative.
              </p>
            </div>

            <Link
              to="/dashboard"
              className="inline-flex items-center gap-2 px-4 py-2 rounded-md font-mono text-xs font-semibold bg-[#141A20] text-[#E4E8EE] hover:bg-[#1A2129] border border-[#2A323C] hover:border-[#7FA3C4]/40 transition-colors"
            >
              <span>View Full Dashboard</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </Link>
          </div>

          <ProductPreview />
        </div>
      </section>

      {/* Section 6: Final Call to Action */}
      <section className="px-6 md:px-12 lg:px-20 py-24 bg-gradient-to-b from-[#0C1014] to-[#141A20] border-b border-[#1F262E] text-center">
        <div className="max-w-3xl mx-auto space-y-6">
          <h2 className="text-3xl md:text-4xl font-mono font-bold text-[#E4E8EE] tracking-tight">
            Turn software failures into actionable findings.
          </h2>

          <p className="text-base text-[#A7B0BC] leading-relaxed font-sans max-w-xl mx-auto">
            Stop guessing why production crashed. TraceBack correlates repository history and runtime telemetry with mathematical precision.
          </p>

          <div className="flex flex-wrap items-center justify-center gap-4 pt-4">
            <Link
              to="/dashboard"
              className="inline-flex items-center gap-2 px-6 py-3.5 rounded-lg font-mono text-sm font-semibold bg-[#3A6C96] text-[#FFFFFF] hover:bg-[#457CAB] transition-colors shadow-sm"
            >
              <span>Open TraceBack Workspace</span>
              <ArrowRight className="w-4 h-4" />
            </Link>

            <Link
              to="/new"
              className="inline-flex items-center gap-2 px-6 py-3.5 rounded-lg font-mono text-sm font-semibold bg-[#141A20] text-[#E4E8EE] hover:bg-[#1A2129] border border-[#2A323C] hover:border-[#7FA3C4]/40 transition-colors"
            >
              <Terminal className="w-4 h-4 text-[#7FA3C4]" />
              <span>Launch New Investigation</span>
            </Link>
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="px-6 md:px-12 lg:px-20 py-12 bg-[#0C1014] text-xs font-mono text-[#6B7585]">
        <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-6">
          <div className="flex items-center gap-3">
            <img
              src="/assets/traceback-logo.png"
              alt="TraceBack Logo"
              className="h-6 w-auto object-contain shrink-0"
            />
            <span className="text-[#E4E8EE] font-bold">TraceBack 2.0</span>
            <span>&bull;</span>
            <span>Evidence-Driven Software Investigation</span>
          </div>

          <div className="flex flex-wrap items-center gap-6 text-[#A7B0BC]">
            <Link to="/dashboard" className="hover:text-[#E4E8EE] transition-colors">
              Dashboard
            </Link>
            <Link to="/new" className="hover:text-[#E4E8EE] transition-colors">
              New Investigation
            </Link>
            <Link to="/history" className="hover:text-[#E4E8EE] transition-colors">
              History
            </Link>
            <Link to="/settings" className="hover:text-[#E4E8EE] transition-colors">
              Settings &amp; API
            </Link>
          </div>

          <div className="text-[11px] text-[#6B7585]">
            Spring Boot 3.3.4 &bull; Java 21 &bull; PostgreSQL 15 &bull; Bifrost Gateway
          </div>
        </div>
      </footer>
    </div>
  );
};
