import React from 'react';
import {
  Terminal,
  GitCommit,
  Hash,
  Sparkles,
  CheckCircle2,
  ArrowDown,
} from 'lucide-react';

export const WorkflowVisualization: React.FC = () => {
  return (
    <div className="rounded-xl border border-[#2A323C] bg-[#141A20] shadow-xl overflow-hidden font-mono text-xs">
      {/* Terminal Title Bar */}
      <div className="px-4 py-2.5 bg-[#0E1216] border-b border-[#1F262E] flex items-center justify-between select-none">
        <div className="flex items-center gap-2">
          <div className="w-2.5 h-2.5 rounded-full bg-[#6B7585]/60" />
          <div className="w-2.5 h-2.5 rounded-full bg-[#6B7585]/40" />
          <div className="w-2.5 h-2.5 rounded-full bg-[#6B7585]/20" />
          <span className="text-[11px] text-[#A7B0BC] ml-2 font-medium">
            traceback-orchestrator.pipeline
          </span>
        </div>
        <div className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C] text-[10px] text-[#7FA3C4]">
          <span className="w-1.5 h-1.5 rounded-full bg-[#7FA3C4]" />
          <span>FORENSIC PIPELINE</span>
        </div>
      </div>

      {/* Pipeline Content: Stack Trace -> Git Changes -> Evidence Correlation -> AI Reasoning -> Investigation Verdict */}
      <div className="p-5 space-y-3 bg-[#0C1014]/70">
        {/* Step 1: Stack Trace */}
        <div className="p-3 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-1.5 transition-all duration-300 hover:border-[#7FA3C4]/60 hover:bg-[#1A2129]/60">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-[#7FA3C4] font-semibold flex items-center gap-1.5">
              <Terminal className="w-3.5 h-3.5" />
              1. Stack Trace Ingestion
            </span>
            <span className="text-[10px] px-1.5 py-0.2 rounded bg-[#1A2129] text-[#B0B8C4] border border-[#2A323C]">
              Java Exception
            </span>
          </div>
          <div className="text-[11px] text-[#E4E8EE] bg-[#0C1014] p-2 rounded border border-[#1F262E] overflow-x-auto">
            <span className="text-[#C86A6A]">NullPointerException</span>: Cannot invoke <span className="text-[#7FA3C4]">Customer.getId()</span> because &quot;customer&quot; is null
            <div className="text-[#828D9E] text-[10px] mt-0.5">
              &gt; at com.example.OrderProcessingService.processOrder(OrderProcessingService.java:42)
            </div>
          </div>
        </div>

        {/* Connector */}
        <div className="flex justify-center -my-1 text-[#7FA3C4]/50 animate-pulse">
          <ArrowDown className="w-3.5 h-3.5" />
        </div>

        {/* Step 2: Git Changes */}
        <div className="p-3 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-1.5 transition-all duration-300 hover:border-[#7FA3C4]/60 hover:bg-[#1A2129]/60">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-[#7FA3C4] font-semibold flex items-center gap-1.5">
              <GitCommit className="w-3.5 h-3.5" />
              2. Git Changes Correlation
            </span>
            <span className="text-[10px] text-[#4FA88A] font-semibold">
              Overlap: Line 42 Match
            </span>
          </div>
          <div className="flex items-center justify-between text-[11px] bg-[#0C1014] p-2 rounded border border-[#1F262E]">
            <div className="flex items-center gap-2 truncate">
              <span className="text-[#E4E8EE] font-bold">c1a89b3</span>
              <span className="text-[#B0B8C4] truncate">
                Refactor customer validation in OrderProcessingService
              </span>
            </div>
            <span className="text-[10px] text-[#828D9E] shrink-0 ml-2">12m prior</span>
          </div>
        </div>

        {/* Connector */}
        <div className="flex justify-center -my-1 text-[#7FA3C4]/50 animate-pulse">
          <ArrowDown className="w-3.5 h-3.5" />
        </div>

        {/* Step 3: Evidence Correlation */}
        <div className="p-3 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-1.5 transition-all duration-300 hover:border-[#7FA3C4]/60 hover:bg-[#1A2129]/60">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-[#7FA3C4] font-semibold flex items-center gap-1.5">
              <Hash className="w-3.5 h-3.5" />
              3. Evidence Correlation
            </span>
            <span className="text-[10px] text-[#B0B8C4]">
              5 Telemetry Items
            </span>
          </div>
          <div className="text-[10px] text-[#B0B8C4] bg-[#0C1014] p-2 rounded border border-[#1F262E] space-y-1">
            <div className="flex items-center justify-between">
              <span className="text-[#7FA3C4]">EVD-GIT-c1a89b3</span>
              <span className="text-[#828D9E] font-mono">SHA-256: d23bba4c443e...</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[#7FA3C4]">EVD-STACK-ROOT</span>
              <span className="text-[#828D9E] font-mono">SHA-256: 8fa901c0b321...</span>
            </div>
          </div>
        </div>

        {/* Connector */}
        <div className="flex justify-center -my-1 text-[#7FA3C4]/50 animate-pulse">
          <ArrowDown className="w-3.5 h-3.5" />
        </div>

        {/* Step 4: AI Reasoning */}
        <div className="p-3 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-1.5 transition-all duration-300 hover:border-[#7FA3C4]/60 hover:bg-[#1A2129]/60">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-[#7FA3C4] font-semibold flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5" />
              4. AI-Assisted Reasoning
            </span>
            <span className="text-[10px] px-1.5 py-0.2 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
              Gemini Flash via Bifrost
            </span>
          </div>
          <div className="text-[10px] text-[#B0B8C4] bg-[#0C1014] p-2 rounded border border-[#1F262E] leading-relaxed">
            Commit <code className="text-[#E4E8EE]">c1a89b3</code> removed null validation for <code className="text-[#7FA3C4]">Customer</code> object, causing downstream dereference exception on line 42.
          </div>
        </div>

        {/* Connector */}
        <div className="flex justify-center -my-1 text-[#7FA3C4]/50 animate-pulse">
          <ArrowDown className="w-3.5 h-3.5" />
        </div>

        {/* Step 5: Investigation Verdict */}
        <div className="p-3 rounded-lg border border-[#4FA88A]/60 bg-[#141A20] flex items-center justify-between shadow-[0_0_12px_rgba(79,168,138,0.12)] transition-all duration-300 hover:border-[#4FA88A]">
          <div className="flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 text-[#4FA88A]" />
            <div>
              <div className="text-[11px] font-bold text-[#E4E8EE]">
                5. Investigation Verdict: CONFIRMED
              </div>
              <div className="text-[10px] text-[#828D9E]">
                Heuristic Formula: S = T + O + A − C = 85.0 pts
              </div>
            </div>
          </div>
          <span className="text-[10px] font-mono px-2 py-0.5 rounded border border-[#4FA88A]/40 text-[#4FA88A] bg-[#4FA88A]/10 font-semibold">
            GROUNDED
          </span>
        </div>
      </div>
    </div>
  );
};
