import React from 'react';
import {
  Cpu,
  Layers,
  Info,
  HelpCircle,
  AlertCircle,
} from 'lucide-react';
import type { InvestigationMode } from '../../api/types';

interface InvestigationOverviewPanelProps {
  serviceName: string;
  environment: string;
  investigationMode: InvestigationMode;
  repositoryPath: string;
  windowMinutes: number;
  maxCommits: number;
  hasStackTrace: boolean;
  hasLogContent: boolean;
  onLoadHikariCP: () => void;
  onLoadPaymentRegression: () => void;
}

export const InvestigationOverviewPanel: React.FC<InvestigationOverviewPanelProps> = ({
  serviceName,
  environment,
  investigationMode,
  repositoryPath,
  windowMinutes,
  maxCommits,
  hasStackTrace,
  hasLogContent,
  onLoadHikariCP,
  onLoadPaymentRegression,
}) => {
  const missingInputs: string[] = [];
  if (!repositoryPath.trim()) missingInputs.push('Repository path');
  if (!serviceName.trim()) missingInputs.push('Service name');
  if (!environment.trim()) missingInputs.push('Environment');
  if (investigationMode === 'RUNTIME_FAILURE' && !hasStackTrace && !hasLogContent) {
    missingInputs.push('Stack trace or logs');
  }

  const isTelemetryReady = missingInputs.length === 0;

  const getRepoBasename = (path: string) => {
    if (!path.trim()) return 'None specified';
    const normalized = path.replace(/\\/g, '/');
    const segments = normalized.split('/').filter(Boolean);
    return segments[segments.length - 1] || path;
  };

  return (
    <div className="space-y-4 font-sans select-none">
      {/* 1. Investigation Configuration Overview Card */}
      <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-4 space-y-3.5 shadow-xs">
        <div className="flex items-center justify-between pb-2 border-b border-[#1F262E]">
          <span className="text-xs font-mono font-bold text-[#E4E8EE] uppercase tracking-wider flex items-center gap-1.5">
            <Layers className="w-3.5 h-3.5 text-[#7FA3C4]" />
            Investigation Overview
          </span>
          <span
            className={`text-[10px] font-mono px-2 py-0.5 rounded border font-semibold ${
              isTelemetryReady
                ? 'bg-transparent text-[#4FA88A] border-[#4FA88A]/30'
                : 'bg-transparent text-[#C9A24A] border-[#C9A24A]/30'
            }`}
          >
            {isTelemetryReady ? 'READY TO DISPATCH' : `AWAITING INPUTS (${missingInputs.length} missing)`}
          </span>
        </div>

        {/* Explain Missing Inputs if not ready */}
        {!isTelemetryReady && (
          <div className="p-2.5 rounded-lg bg-[#C9A24A]/10 border border-[#C9A24A]/30 text-[11px] text-[#E5A84B] flex items-start gap-1.5">
            <AlertCircle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
            <div>
              <span className="font-semibold block font-mono">Missing Required Inputs:</span>
              <span>{missingInputs.join(' · ')}</span>
            </div>
          </div>
        )}

        {/* Configuration Summary Key-Values */}
        <div className="space-y-2 text-xs font-mono">
          <div className="flex items-center justify-between text-[#B0B8C4]">
            <span className="text-[#828D9E]">Service:</span>
            <span className="text-[#E4E8EE] font-semibold truncate max-w-[150px]">
              {serviceName || <span className="text-[#828D9E] italic">Unset</span>}
            </span>
          </div>

          <div className="flex items-center justify-between text-[#B0B8C4]">
            <span className="text-[#828D9E]">Environment:</span>
            <span className="text-[#7FA3C4] font-semibold">
              {environment || <span className="text-[#828D9E] italic">Unset</span>}
            </span>
          </div>

          <div className="flex items-center justify-between text-[#B0B8C4]">
            <span className="text-[#828D9E]">Investigation Mode:</span>
            <span className="text-[11px] font-semibold px-1.5 py-0.2 rounded bg-[#1A2129] text-[#7FA3C4] border border-[#2A323C]">
              {investigationMode === 'RUNTIME_FAILURE'
                ? 'Runtime Execution'
                : 'Working-Tree Delta'}
            </span>
          </div>

          <div className="flex items-center justify-between text-[#B0B8C4]">
            <span className="text-[#828D9E]">Target Repository:</span>
            <span
              className="text-[#E4E8EE] truncate max-w-[150px]"
              title={repositoryPath || 'None'}
            >
              {getRepoBasename(repositoryPath)}
            </span>
          </div>

          <div className="flex items-center justify-between text-[#B0B8C4] pt-1 border-t border-[#1F262E]">
            <span className="text-[#828D9E]">Pre-Incident Window:</span>
            <span className="text-[#E4E8EE]">{windowMinutes} minutes</span>
          </div>

          <div className="flex items-center justify-between text-[#B0B8C4]">
            <span className="text-[#828D9E]">Search Depth:</span>
            <span className="text-[#E4E8EE]">Max {maxCommits} commits</span>
          </div>
        </div>
      </div>

      {/* 2. What TraceBack Will Do (Educational / Architectural Flow) */}
      <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-4 space-y-3 shadow-xs">
        <span className="text-xs font-mono font-bold text-[#E4E8EE] uppercase tracking-wider flex items-center gap-1.5">
          <Cpu className="w-3.5 h-3.5 text-[#7FA3C4]" />
          What TraceBack Will Do
        </span>

        <ol className="space-y-2.5 text-xs text-[#B0B8C4] font-sans">
          <li className="flex items-start gap-2">
            <span className="w-4 h-4 rounded bg-[#1A2129] text-[#7FA3C4] font-mono text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 border border-[#2A323C]">
              1
            </span>
            <span>
              <strong className="text-[#E4E8EE]">Parse telemetry</strong> — Extracts exception class, failing method, and exact line coordinates.
            </span>
          </li>
          <li className="flex items-start gap-2">
            <span className="w-4 h-4 rounded bg-[#1A2129] text-[#7FA3C4] font-mono text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 border border-[#2A323C]">
              2
            </span>
            <span>
              <strong className="text-[#E4E8EE]">Inspect repository changes</strong> — Scans commits within the {windowMinutes}m pre-incident window.
            </span>
          </li>
          <li className="flex items-start gap-2">
            <span className="w-4 h-4 rounded bg-[#1A2129] text-[#7FA3C4] font-mono text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 border border-[#2A323C]">
              3
            </span>
            <span>
              <strong className="text-[#E4E8EE]">Correlate evidence</strong> — Computes temporal ($T$), code overlap ($O$), and multi-signal agreement ($A$).
            </span>
          </li>
          <li className="flex items-start gap-2">
            <span className="w-4 h-4 rounded bg-[#1A2129] text-[#7FA3C4] font-mono text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 border border-[#2A323C]">
              4
            </span>
            <span>
              <strong className="text-[#E4E8EE]">AI cognitive synthesis</strong> — Evaluates candidate hypotheses and verification steps via Gemini Flash.
            </span>
          </li>
          <li className="flex items-start gap-2">
            <span className="w-4 h-4 rounded bg-[#1A2129] text-[#7FA3C4] font-mono text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 border border-[#2A323C]">
              5
            </span>
            <span>
              <strong className="text-[#E4E8EE]">Produce dossier</strong> — Delivers an evidence-grounded report with SHA-256 registered checksums.
            </span>
          </li>
        </ol>

        {/* Evidence Dependency Discloser */}
        <div className="p-2.5 rounded-lg bg-[#0C1014] border border-[#1F262E] text-[11px] text-[#828D9E] flex items-start gap-2 leading-relaxed">
          <Info className="w-3.5 h-3.5 text-[#7FA3C4] shrink-0 mt-0.5" />
          <span>
            Notice: Final root-cause verdicts depend strictly on verifiable repository diffs and telemetry evidence.
          </span>
        </div>
      </div>

      {/* 3. Demo Case Quick-Loader */}
      <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-4 space-y-3 shadow-xs">
        <div className="flex items-center justify-between">
          <span className="text-xs font-mono font-bold text-[#B0B8C4] uppercase tracking-wider flex items-center gap-1.5">
            <HelpCircle className="w-3.5 h-3.5 text-[#7FA3C4]" />
            Sample Failure Fixtures
          </span>
          <span className="text-[10px] font-mono text-[#7FA3C4] px-1.5 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
            Demo Preset
          </span>
        </div>

        <p className="text-xs text-[#828D9E] font-sans">
          Load verified failure fixtures to test autonomous diagnosis (does not insert database records until started):
        </p>

        <div className="space-y-2 font-mono">
          <button
            type="button"
            onClick={onLoadHikariCP}
            className="w-full px-3 py-2 rounded-lg bg-[#0C1014] hover:bg-[#1A2129] text-[#E4E8EE] border border-[#2A323C] hover:border-[#7FA3C4]/50 text-xs transition-colors text-left flex items-center justify-between"
          >
            <span>Scenario B: HikariCP Pool Timeout</span>
            <span className="text-[10px] text-[#7FA3C4]">Load Fixture &rarr;</span>
          </button>

          <button
            type="button"
            onClick={onLoadPaymentRegression}
            className="w-full px-3 py-2 rounded-lg bg-[#0C1014] hover:bg-[#1A2129] text-[#E4E8EE] border border-[#2A323C] hover:border-[#7FA3C4]/50 text-xs transition-colors text-left flex items-center justify-between"
          >
            <span>Scenario A: Division by Zero</span>
            <span className="text-[10px] text-[#7FA3C4]">Load Fixture &rarr;</span>
          </button>
        </div>
      </div>
    </div>
  );
};
