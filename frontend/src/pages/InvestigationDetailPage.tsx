import React, { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  Sparkles,
  GitBranch,
  ShieldCheck,
  FileText,
  Clock,
  Globe,
  Terminal,
  AlertCircle,
  Copy,
  Check,
  ChevronRight,
  FolderOpen,
  Server,
  Layers,
  Cpu,
  Wrench,
} from 'lucide-react';
import { HypothesesList } from '../components/investigation/HypothesesList';
import { ConfidenceBreakdownView } from '../components/investigation/ConfidenceBreakdownView';
import { ClaimValidationTable } from '../components/investigation/ClaimValidationTable';
import { ExternalResearchList } from '../components/investigation/ExternalResearchList';
import { AgentAuditTrail } from '../components/investigation/AgentAuditTrail';
import { EvidenceExplorer } from '../components/evidence/EvidenceExplorer';
import { ChronologicalTimeline } from '../components/timeline/ChronologicalTimeline';
import { AiReasoningPanel } from '../components/investigation/AiReasoningPanel';
import { ContextualInspector, type InspectorSelection } from '../components/investigation/ContextualInspector';
import { RemediationOperationsList } from '../components/remediation/RemediationOperationsList';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { StatusPill } from '../components/shared/StatusPill';
import { Badge } from '../components/shared/Badge';
import { formatDate } from '../utils/formatters';
import { investigationApi } from '../api/investigationApi';
import type {
  AgentInvestigationStep,
  EvidenceItem,
  ExternalResearchFinding,
  HypothesisResponse,
  InvestigationAgentReport,
  InvestigationClaim,
  InvestigationResponse,
  TimelineEvent,
} from '../api/types';

export const InvestigationDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();

  // Active Tab
  const [activeTab, setActiveTab] = useState<
    'overview' | 'hypotheses' | 'claims' | 'evidence' | 'timeline' | 'research' | 'audit' | 'remediations'
  >('overview');
  const [remediationCount, setRemediationCount] = useState<number>(0);
  const [selectedEvidenceId, setSelectedEvidenceId] = useState<string | null>(null);
  const [selectedItem, setSelectedItem] = useState<InspectorSelection>(null);
  const [copiedId, setCopiedId] = useState(false);

  // Tab Strip Scroll State & Refs
  const tabsContainerRef = useRef<HTMLDivElement>(null);

  const copyId = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(true);
      setTimeout(() => setCopiedId(false), 2000);
    } catch {
      // ignore
    }
  };

  const handleSelectEvidence = (evidenceId: string) => {
    setSelectedEvidenceId(evidenceId);
    setActiveTab('evidence');
    const found = evidenceItems.find((e) => e.evidenceId === evidenceId);
    if (found) {
      setSelectedItem({ type: 'evidence', item: found });
    } else {
      setSelectedItem({
        type: 'evidence',
        item: {
          evidenceId,
          evidenceType: 'COMMIT',
          source: 'Investigation Telemetry',
          summary: `Evidence record ${evidenceId}`,
        },
      });
    }
  };

  // Loaded Data States
  const [agentReport, setAgentReport] = useState<InvestigationAgentReport | null>(null);
  const [deterministicInfo, setDeterministicInfo] = useState<InvestigationResponse | null>(null);
  const [hypotheses, setHypotheses] = useState<HypothesisResponse[]>([]);
  const [claims, setClaims] = useState<InvestigationClaim[]>([]);
  const [evidenceItems, setEvidenceItems] = useState<EvidenceItem[]>([]);
  const [timelineEvents, setTimelineEvents] = useState<TimelineEvent[]>([]);
  const [researchFindings, setResearchFindings] = useState<ExternalResearchFinding[]>([]);
  const [agentSteps, setAgentSteps] = useState<AgentInvestigationStep[]>([]);

  // Status
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAllData = async () => {
    if (!id) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setError(null);

    try {
      // 1. Fetch Agent Report
      let report: InvestigationAgentReport | null = null;
      try {
        report = await investigationApi.getAgentReport(id);
        setAgentReport(report);
      } catch {
        setAgentReport(null);
      }

      // 2. Fetch Deterministic Investigation Details
      try {
        const det = await investigationApi.getInvestigation(id);
        setDeterministicInfo(det);
      } catch {
        // deterministic endpoint might not have this id if agent was run standalone
      }

      // 3. Fetch Sub-resources in parallel
      const [hypoData, claimData, evdData, timeData, resData, stepData] = await Promise.all([
        investigationApi.getHypotheses(id).catch(() => []),
        investigationApi.getClaims(id).catch(() => []),
        investigationApi.getEvidence(id).catch(() => []),
        investigationApi.getTimeline(id).catch(() => []),
        investigationApi.getResearchFindings(id).catch(() => []),
        investigationApi.getAgentSteps(id).catch(() => []),
      ]);

      setHypotheses(hypoData);
      const authoritativeClaims = (report?.validatedClaims && report.validatedClaims.length > 0)
        ? report.validatedClaims
        : claimData;
      setClaims(authoritativeClaims);
      setEvidenceItems(evdData);
      setTimelineEvents(timeData);
      const normalizedResearch: ExternalResearchFinding[] =
        resData.length > 0
          ? resData
          : (report?.externalResearchFindings || []).map((item, idx) =>
              typeof item === 'string'
                ? { findingId: `res-${idx}`, query: '', title: item, url: '', snippet: item, retrievedAt: '', relevanceScore: 1 }
                : item
            );
      setResearchFindings(normalizedResearch);
      setAgentSteps(
        stepData.length > 0
          ? stepData
          : report?.investigationSteps || []
      );
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to retrieve investigation');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchAllData();
  }, [id]);

  if (isLoading) {
    return (
      <div className="p-16 flex items-center justify-center font-sans">
        <LoadingSpinner label="Retrieving investigation dossier and evidence artifacts..." size="lg" />
      </div>
    );
  }

  if (error || (!agentReport && !deterministicInfo)) {
    return (
      <div className="p-8 max-w-2xl mx-auto space-y-4 text-center font-sans">
        <div className="p-5 rounded-xl border border-rose-500/30 bg-rose-500/10 text-xs text-rose-300">
          <AlertCircle className="w-6 h-6 mx-auto mb-2 text-rose-400" />
          <h3 className="font-mono font-bold text-sm mb-1">Investigation Dossier Not Found</h3>
          <p className="font-sans leading-relaxed">{error || `No investigation with ID '${id}' found in database records.`}</p>
        </div>
        <div className="flex justify-center gap-3">
          <button
            onClick={fetchAllData}
            className="px-3.5 py-2 rounded-lg border border-[#2A323C] bg-[#141A20] text-xs font-mono text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors"
          >
            Retry Request
          </button>
          <Link
            to="/history"
            className="px-3.5 py-2 rounded-lg bg-[#3A6C96] text-white text-xs font-mono font-semibold hover:bg-[#457CAB] transition-colors"
          >
            Return to History
          </Link>
        </div>
      </div>
    );
  }

  // Derive display values
  const invId = id || agentReport?.investigationId || deterministicInfo?.investigationId || 'UNKNOWN';
  const service = deterministicInfo?.serviceName || 'application-service';
  const env = deterministicInfo?.environment || 'Local Development';
  const mode = deterministicInfo?.investigationMode || (agentReport?.primarySuspectedRootCause?.includes('Working tree') ? 'WORKING_TREE_FORENSICS' : undefined);
  const verdict = agentReport?.finalStatus || deterministicInfo?.status || 'PROBABLE';
  const summary = agentReport?.summary || deterministicInfo?.topHypothesis?.summary || 'Forensic investigation completed.';
  const primarySuspect = agentReport?.primarySuspectedRootCause || deterministicInfo?.topHypothesis?.summary;
  const createdAt = agentReport?.generatedAt || deterministicInfo?.startedAt || new Date().toISOString();
  const isFallback = agentReport?.aiReasoningSummary?.includes('Deterministic fallback') || false;

  const supportingCount = agentReport?.supportingEvidenceIds?.length ?? (evidenceItems.length > 0 ? evidenceItems.length : 0);
  const contradictoryCount = agentReport?.contradictoryEvidenceIds?.length ?? 0;

  const breakdown =
    agentReport?.deterministicConfidenceBreakdown ||
    hypotheses[0]?.confidenceBreakdown ||
    deterministicInfo?.topHypothesis?.confidenceBreakdown ||
    null;

  // Real candidate score from deterministic breakdown or top hypothesis (NO fabricated 0.95/0.75 fallbacks)
  const candidateScore: number | undefined =
    agentReport?.deterministicConfidenceBreakdown?.normalizedScore ??
    breakdown?.normalizedScore ??
    hypotheses[0]?.confidenceScore ??
    deterministicInfo?.topHypothesis?.confidenceScore ??
    undefined;

  const getRootCauseBorderClass = (st: string) => {
    const norm = st.toUpperCase();
    if (norm === 'CONFIRMED' || norm === 'SUPPORTED') return 'border-l-[#4FA88A]';
    if (norm === 'PROBABLE' || norm === 'PARTIALLY_SUPPORTED' || norm === 'PROPOSED') return 'border-l-[#C9A24A]';
    return 'border-l-[#7C8796]';
  };

  const isSuspiciousLowEvidence =
    verdict.toUpperCase() === 'CONFIRMED' &&
    supportingCount < 1;

  const tabs = [
    { id: 'overview', label: 'AI Report & Overview', icon: Sparkles, count: undefined },
    { id: 'hypotheses', label: `Hypotheses (${hypotheses.length})`, icon: GitBranch, count: hypotheses.length },
    { id: 'claims', label: `Claims (${claims.length})`, icon: ShieldCheck, count: claims.length },
    { id: 'evidence', label: `Evidence (${evidenceItems.length})`, icon: FileText, count: evidenceItems.length },
    { id: 'timeline', label: `Timeline (${timelineEvents.length})`, icon: Clock, count: timelineEvents.length },
    { id: 'research', label: `Research (${researchFindings.length})`, icon: Globe, count: researchFindings.length },
    { id: 'audit', label: `Agent Steps (${agentSteps.length})`, icon: Terminal, count: agentSteps.length },
    { id: 'remediations', label: `Remediations (${remediationCount})`, icon: Wrench, count: remediationCount },
  ];

  return (
    <div className="space-y-4 font-sans select-none">
      {/* Sticky Case File Context Strip (always visible while scrolling) */}
      <div className="sticky top-0 z-10 bg-[#0E1216]/95 backdrop-blur-md border border-[#2A323C] p-3 rounded-lg shadow-sm flex flex-wrap items-center justify-between gap-3 font-mono text-xs">
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Breadcrumb nav */}
          <div className="flex items-center gap-1.5 text-[#6B7585]">
            <Link to="/history" className="hover:text-[#7FA3C4] transition-colors flex items-center gap-1">
              <FolderOpen className="w-3.5 h-3.5" />
              <span>Investigations</span>
            </Link>
            <ChevronRight className="w-3 h-3 text-[#2A323C]" />
          </div>

          {/* ID chip with copy button */}
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-[#141A20] border border-[#1F262E] text-[#A7B0BC]">
            <span className="text-[#6B7585]">ID:</span>
            <span className="text-[#E4E8EE]">{invId}</span>
            <button
              onClick={() => copyId(invId)}
              className="ml-0.5 text-[#6B7585] hover:text-[#E4E8EE] focus:outline-none"
              title="Copy Investigation ID"
            >
              {copiedId ? <Check className="w-3 h-3 text-[#4FA88A]" /> : <Copy className="w-3 h-3" />}
            </button>
          </div>

          {/* Service & Environment */}
          <div className="flex items-center gap-1 text-[#E4E8EE] px-2 py-0.5 rounded bg-[#1A2129] border border-[#1F262E]">
            <Server className="w-3 h-3 text-[#7FA3C4]" />
            <span className="font-semibold">{service}</span>
          </div>

          <div className="flex items-center gap-1 text-[#A7B0BC] px-2 py-0.5 rounded bg-[#1A2129] border border-[#1F262E]">
            <Layers className="w-3 h-3 text-[#6B7585]" />
            <span className="capitalize">{env}</span>
          </div>

          {mode && (
            <Badge variant="blue" mono size="sm">
              {mode.replace(/_/g, ' ')}
            </Badge>
          )}
        </div>

        {/* Right side verdict & score in context strip */}
        <div className="flex items-center gap-2.5">
          {isFallback && (
            <span
              className="text-[10px] px-2 py-0.5 rounded bg-[#1A2129] text-[#C9A24A] border border-[#2A323C] font-semibold"
              title="Generated using deterministic rules because Bifrost AI Gateway was offline or unconfigured"
            >
              DETERMINISTIC FALLBACK
            </span>
          )}
          <StatusPill status={verdict} size="sm" />
          {candidateScore !== undefined ? (
            <span
              className="text-xs text-[#7FA3C4] font-bold tabular-nums flex items-center gap-1"
              title="Deterministic ranking score based on heuristic formula (S = T + O + A - C, not a statistical probability)"
            >
              <span className="text-[10px] text-[#6B7585] uppercase font-normal">Candidate Score:</span>
              <span>{(candidateScore * 100).toFixed(0)}%</span>
            </span>
          ) : (
            <span
              className="text-xs text-[#6B7585] font-mono flex items-center gap-1 px-2 py-0.5 rounded bg-[#141A20] border border-[#1F262E]"
              title={verdict === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry is insufficient to score candidate correlation' : 'No deterministic candidate score recorded'}
            >
              <span className="text-[10px] text-[#6B7585] uppercase font-normal">Candidate Score:</span>
              <span className="text-[#A7B0BC] italic">
                {verdict === 'INSUFFICIENT_EVIDENCE' ? 'Telemetry Insufficient' : 'Unscored'}
              </span>
            </span>
          )}
        </div>
      </div>

      {/* 3-ZONE FORENSIC CASE FILE WORKBENCH */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
        {/* ================= ZONE 1: Section Index (Left 2 cols) ================= */}
        <div className="lg:col-span-2 space-y-3">
          <div className="rounded-lg border border-[#2A323C] bg-[#141A20] p-2 space-y-1">
            <div className="px-2.5 py-1.5 text-[10px] font-mono uppercase tracking-wider text-[#6B7585] font-semibold border-b border-[#1F262E]">
              Case Sections
            </div>

            {/* Vertical Index Rail */}
            <div
              ref={tabsContainerRef}
              role="tablist"
              aria-label="Investigation Dossier Tabs"
              className="flex flex-row lg:flex-col gap-1 overflow-x-auto lg:overflow-x-visible py-1 select-none"
            >
              {tabs.map((tab) => {
                const Icon = tab.icon;
                const isActive = activeTab === tab.id;
                return (
                  <button
                    key={tab.id}
                    role="tab"
                    aria-selected={isActive}
                    data-active={isActive ? 'true' : 'false'}
                    data-tab-id={tab.id}
                    onClick={() => setActiveTab(tab.id as typeof activeTab)}
                    className={`flex items-center justify-between gap-2 px-2.5 py-2 rounded-md text-xs font-mono transition-all text-left whitespace-nowrap lg:whitespace-normal shrink-0 ${
                      isActive
                        ? 'bg-[#1A2129] text-[#E4E8EE] border-l-2 border-[#7FA3C4] font-semibold shadow-xs'
                        : 'text-[#A7B0BC] hover:text-[#E4E8EE] hover:bg-[#1A2129]/60'
                    }`}
                  >
                    <div className="flex items-center gap-2 truncate">
                      <Icon className={`w-3.5 h-3.5 shrink-0 ${isActive ? 'text-[#7FA3C4]' : 'text-[#6B7585]'}`} />
                      <span className="truncate">{tab.label}</span>
                    </div>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Quick Case File Profile Card */}
          <div className="p-3 rounded-lg border border-[#1F262E] bg-[#141A20] space-y-2 text-[11px] font-mono text-[#6B7585] hidden lg:block">
            <div className="text-[10px] uppercase tracking-wider text-[#A7B0BC] font-semibold">
              Registry File
            </div>
            <div className="space-y-1 pt-1 border-t border-[#1F262E]">
              <div className="flex justify-between">
                <span>Opened:</span>
                <span className="text-[#A7B0BC]">{formatDate(createdAt)}</span>
              </div>
              <div className="flex justify-between">
                <span>Evidence:</span>
                <span className="text-[#A7B0BC]">{supportingCount} registered</span>
              </div>
              <div className="flex justify-between">
                <span>Claims:</span>
                <span className="text-[#A7B0BC]">{claims.length} grounded</span>
              </div>
            </div>
          </div>
        </div>

        {/* ================= ZONE 2: Main Reading & Analysis Area (Center 7 cols) ================= */}
        <div className="lg:col-span-7 space-y-5">
          {/* Executive Case Summary & Primary Root Cause */}
          <div className="p-4 rounded-lg border border-[#2A323C] bg-[#141A20] space-y-3.5">
            <div>
              <div className="flex items-center justify-between mb-1">
                <span className="text-[11px] font-mono uppercase tracking-wider text-[#6B7585] font-semibold flex items-center gap-1.5">
                  <Cpu className="w-3.5 h-3.5 text-[#7FA3C4]" />
                  Executive Diagnostic Summary
                </span>
                <span className="text-[10px] font-mono text-[#6B7585]">
                  {formatDate(createdAt)}
                </span>
              </div>
              <p className="text-sm text-[#E4E8EE] font-sans leading-relaxed">
                {summary || 'Forensic investigation completed with deterministic correlation analysis.'}
              </p>
            </div>

            {primarySuspect && (
              <div className={`p-3.5 rounded-lg border-l-[3px] ${getRootCauseBorderClass(verdict)} border-t border-r border-b border-[#1F262E] bg-[#0C1014] space-y-1`}>
                <span className="text-xs font-mono uppercase tracking-wider text-[#E4E8EE] font-bold block">
                  Primary Suspected Root Cause
                </span>
                <p className="text-xs font-mono text-[#A7B0BC] leading-relaxed">
                  {primarySuspect}
                </p>
              </div>
            )}

            {isSuspiciousLowEvidence && (
              <div className="p-3 rounded-lg border-l-[3px] border-l-[#C9A24A] border border-[#1F262E] bg-[#0C1014] text-xs text-[#A7B0BC] flex items-start gap-2">
                <AlertCircle className="w-4 h-4 text-[#C9A24A] shrink-0 mt-0.5" />
                <p className="leading-relaxed font-sans text-xs">
                  <strong className="font-semibold text-[#E4E8EE]">Review Advisory:</strong> Investigation status is CONFIRMED with zero registered supporting evidence records. Verify telemetry pipeline ingestion.
                </p>
              </div>
            )}
          </div>

          {/* Dynamic Content Panels based on Active Tab */}
          <div className="space-y-5">
            {/* Tab 1: Overview & AI Report */}
            {activeTab === 'overview' && (
              <div className="space-y-5">
                {agentReport && (
                  <AiReasoningPanel
                    report={agentReport}
                    claims={claims}
                    onSelectEvidence={handleSelectEvidence}
                  />
                )}

                {breakdown && (
                  <ConfidenceBreakdownView
                    breakdown={breakdown}
                    overallScore={
                      hypotheses[0]?.confidenceScore ||
                      deterministicInfo?.topHypothesis?.confidenceScore ||
                      0
                    }
                  />
                )}
              </div>
            )}

            {/* Tab 2: Hypotheses */}
            {activeTab === 'hypotheses' && (
              <div className="space-y-5">
                <HypothesesList
                  hypotheses={hypotheses}
                  onSelectEvidence={handleSelectEvidence}
                  onInspectHypothesis={(h) => setSelectedItem({ type: 'hypothesis', item: h })}
                />
                {(hypotheses[0]?.confidenceBreakdown || agentReport?.deterministicConfidenceBreakdown) && (
                  <ConfidenceBreakdownView
                    breakdown={
                      hypotheses[0]?.confidenceBreakdown ||
                      agentReport?.deterministicConfidenceBreakdown
                    }
                    overallScore={hypotheses[0]?.confidenceScore || 0}
                  />
                )}
              </div>
            )}

            {/* Tab 3: Claim Validation */}
            {activeTab === 'claims' && (
              <div className="space-y-5">
                <ClaimValidationTable
                  claims={claims}
                  onSelectEvidence={handleSelectEvidence}
                  onInspectClaim={(c) => setSelectedItem({ type: 'claim', item: c })}
                />
              </div>
            )}

            {/* Tab 4: Evidence Explorer */}
            {activeTab === 'evidence' && (
              <div className="space-y-5">
                <EvidenceExplorer
                  evidenceItems={evidenceItems}
                  initialSearchTerm=""
                  highlightedEvidenceId={selectedEvidenceId || undefined}
                  onClearHighlight={() => {
                    setSelectedEvidenceId(null);
                    setSelectedItem(null);
                  }}
                  preserveInventory={true}
                  onInspectEvidence={(e) => setSelectedItem({ type: 'evidence', item: e })}
                />
              </div>
            )}

            {/* Tab 5: Timeline */}
            {activeTab === 'timeline' && (
              <div className="space-y-5">
                <ChronologicalTimeline
                  events={timelineEvents}
                  onSelectEvidence={handleSelectEvidence}
                  onInspectTimelineEvent={(evt) => setSelectedItem({ type: 'timeline', item: evt })}
                />
              </div>
            )}

            {/* Tab 6: External Technical Research */}
            {activeTab === 'research' && (
              <div className="space-y-5">
                <ExternalResearchList
                  findings={researchFindings}
                  sourceUrls={agentReport?.sourceUrls}
                />
              </div>
            )}

            {/* Tab 7: Agent Audit Trail */}
            {activeTab === 'audit' && (
              <div className="space-y-5">
                <AgentAuditTrail steps={agentSteps} />
              </div>
            )}

            {/* Tab 8: Remediation Operations Journal */}
            {activeTab === 'remediations' && (
              <div className="space-y-5">
                <RemediationOperationsList
                  investigationId={id || ''}
                  onCountChange={setRemediationCount}
                />
              </div>
            )}
          </div>
        </div>

        {/* ================= ZONE 3: Contextual Inspector (Right 3 cols) ================= */}
        <div className="lg:col-span-3 space-y-4 lg:sticky lg:top-16">
          <ContextualInspector
            selection={selectedItem}
            verdict={verdict}
            candidateScore={candidateScore}
            supportingCount={supportingCount}
            contradictoryCount={contradictoryCount}
            onSelectEvidence={handleSelectEvidence}
            onClearSelection={() => setSelectedItem(null)}
          />
        </div>
      </div>
    </div>
  );
};
