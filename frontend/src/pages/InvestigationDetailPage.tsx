import React, { useEffect, useState } from 'react';
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
  ListChecks,
  HelpCircle,
} from 'lucide-react';
import { SummaryCard } from '../components/investigation/SummaryCard';
import { HypothesesList } from '../components/investigation/HypothesesList';
import { ConfidenceBreakdownView } from '../components/investigation/ConfidenceBreakdownView';
import { ClaimValidationTable } from '../components/investigation/ClaimValidationTable';
import { ExternalResearchList } from '../components/investigation/ExternalResearchList';
import { AgentAuditTrail } from '../components/investigation/AgentAuditTrail';
import { EvidenceExplorer } from '../components/evidence/EvidenceExplorer';
import { ChronologicalTimeline } from '../components/timeline/ChronologicalTimeline';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
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
    'overview' | 'hypotheses' | 'claims' | 'evidence' | 'timeline' | 'research' | 'audit'
  >('overview');

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
    if (!id) return;
    setIsLoading(true);
    setError(null);

    try {
      // 1. Fetch Agent Report
      let report: InvestigationAgentReport | null = null;
      try {
        report = await investigationApi.getAgentReport(id);
        setAgentReport(report);
      } catch {
        // Agent report might not exist yet if only deterministic ran
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
      setClaims(
        claimData.length > 0
          ? claimData
          : report?.validatedClaims || []
      );
      setEvidenceItems(evdData);
      setTimelineEvents(timeData);
      setResearchFindings(
        resData.length > 0
          ? resData
          : report?.externalResearchFindings || []
      );
      setAgentSteps(
        stepData.length > 0
          ? stepData
          : report?.investigationSteps || []
      );

      // If hypotheses array is empty from /hypotheses but agentReport has alternativeHypotheses
      if (hypoData.length === 0 && report && report.alternativeHypotheses) {
        // synthesize response items
      }
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
      <div className="p-12 flex items-center justify-center">
        <LoadingSpinner label="Retrieving investigation dossier and evidence artifacts..." size="lg" />
      </div>
    );
  }

  if (error || (!agentReport && !deterministicInfo)) {
    return (
      <div className="p-8 max-w-2xl mx-auto space-y-4 text-center">
        <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-500/10 text-xs text-rose-300">
          <AlertCircle className="w-6 h-6 mx-auto mb-2 text-rose-400" />
          <h3 className="font-mono font-bold text-sm mb-1">Investigation Dossier Not Found</h3>
          <p>{error || `No investigation with ID '${id}' found in backend memory.`}</p>
        </div>
        <div className="flex justify-center gap-3">
          <button
            onClick={fetchAllData}
            className="px-3 py-1.5 rounded-lg border border-dark-700 bg-dark-800 text-xs font-mono text-dark-200 hover:bg-dark-750"
          >
            Retry Request
          </button>
          <Link
            to="/history"
            className="px-3 py-1.5 rounded-lg bg-brand-amber text-dark-950 text-xs font-mono font-semibold"
          >
            Return to History
          </Link>
        </div>
      </div>
    );
  }

  // Derive display values
  const invId = id || agentReport?.investigationId || deterministicInfo?.investigationId || 'UNKNOWN';
  const service = deterministicInfo?.serviceName || 'order-service';
  const env = deterministicInfo?.environment || 'production';
  const verdict = agentReport?.finalStatus || deterministicInfo?.status || 'PROBABLE';
  const summary = agentReport?.summary || deterministicInfo?.topHypothesis?.summary || 'Deterministic investigation completed.';
  const primarySuspect = agentReport?.primarySuspectedRootCause || deterministicInfo?.topHypothesis?.summary;
  const createdAt = agentReport?.generatedAt || deterministicInfo?.startedAt || new Date().toISOString();
  const isFallback = agentReport?.aiReasoningSummary?.includes('Deterministic fallback') || false;

  const tabs = [
    { id: 'overview', label: 'AI Report & Overview', icon: Sparkles },
    { id: 'hypotheses', label: `Hypotheses (${hypotheses.length})`, icon: GitBranch },
    { id: 'claims', label: `Claims (${claims.length})`, icon: ShieldCheck },
    { id: 'evidence', label: `Evidence (${evidenceItems.length})`, icon: FileText },
    { id: 'timeline', label: `Timeline (${timelineEvents.length})`, icon: Clock },
    { id: 'research', label: `Research (${researchFindings.length})`, icon: Globe },
    { id: 'audit', label: `Agent Steps (${agentSteps.length})`, icon: Terminal },
  ];

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6">
      {/* Primary Investigation Summary Card */}
      <SummaryCard
        investigationId={invId}
        reportId={agentReport?.reportId}
        serviceName={service}
        environment={env}
        status={verdict}
        summary={summary}
        primaryRootCause={primarySuspect}
        createdAt={createdAt}
        isFallback={isFallback}
      />

      {/* Tabs Strip */}
      <div className="flex items-center gap-1.5 p-1 rounded-xl bg-dark-900 border border-dark-700/80 overflow-x-auto select-none">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as typeof activeTab)}
              className={`flex items-center gap-2 px-3.5 py-2 rounded-lg text-xs font-mono font-medium transition-colors whitespace-nowrap ${
                isActive
                  ? 'bg-dark-800 text-brand-amber border border-dark-700 shadow-sm'
                  : 'text-dark-400 hover:text-dark-200 hover:bg-dark-850'
              }`}
            >
              <Icon className="w-3.5 h-3.5 shrink-0" />
              <span>{tab.label}</span>
            </button>
          );
        })}
      </div>

      {/* Tab Panels */}
      <div className="space-y-6">
        {/* Tab 1: Overview & AI Report */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {/* AI Reasoning Narrative */}
            {agentReport && (
              <div className="rounded-xl border border-dark-700 bg-dark-900/80 p-5 space-y-3">
                <div className="flex items-center justify-between pb-2 border-b border-dark-700/80">
                  <h3 className="text-xs font-mono uppercase tracking-wider text-dark-200 font-bold flex items-center gap-2">
                    <Sparkles className="w-3.5 h-3.5 text-brand-amber" />
                    Autonomous Agent Reasoning & Diagnostic Synthesis
                  </h3>
                  <span className="text-[11px] font-mono text-dark-500">
                    Bifrost Gateway / Grounded
                  </span>
                </div>
                <div className="p-3.5 rounded-lg bg-dark-950 border border-dark-800 font-mono text-xs text-dark-200 whitespace-pre-line leading-relaxed">
                  {agentReport.aiReasoningSummary}
                </div>
              </div>
            )}

            {/* Recommended Verification Steps & Remaining Uncertainties */}
            {agentReport && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {/* Verification Steps */}
                <div className="rounded-xl border border-dark-700 bg-dark-900/80 p-5 space-y-3">
                  <h3 className="text-xs font-mono uppercase tracking-wider text-emerald-400 font-bold flex items-center gap-2">
                    <ListChecks className="w-4 h-4" />
                    Recommended Verification Steps
                  </h3>
                  {agentReport.recommendedVerificationSteps &&
                  agentReport.recommendedVerificationSteps.length > 0 ? (
                    <ul className="space-y-2 text-xs font-sans text-dark-200">
                      {agentReport.recommendedVerificationSteps.map((step, idx) => (
                        <li key={idx} className="flex items-start gap-2 bg-dark-950/60 p-2.5 rounded-lg border border-dark-800">
                          <span className="w-4 h-4 rounded-full bg-emerald-500/10 text-emerald-400 font-mono text-[10px] flex items-center justify-center shrink-0 mt-0.5">
                            {idx + 1}
                          </span>
                          <span className="leading-relaxed">{step}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="text-xs text-dark-500 italic">No specific steps suggested.</p>
                  )}
                </div>

                {/* Remaining Uncertainties */}
                <div className="rounded-xl border border-dark-700 bg-dark-900/80 p-5 space-y-3">
                  <h3 className="text-xs font-mono uppercase tracking-wider text-amber-400 font-bold flex items-center gap-2">
                    <HelpCircle className="w-4 h-4" />
                    Remaining Uncertainties & Caveats
                  </h3>
                  {agentReport.remainingUncertainties &&
                  agentReport.remainingUncertainties.length > 0 ? (
                    <ul className="space-y-2 text-xs font-sans text-dark-200">
                      {agentReport.remainingUncertainties.map((unc, idx) => (
                        <li key={idx} className="flex items-start gap-2 bg-dark-950/60 p-2.5 rounded-lg border border-dark-800">
                          <span className="w-4 h-4 rounded-full bg-amber-500/10 text-amber-400 font-mono text-[10px] flex items-center justify-center shrink-0 mt-0.5">
                            ?
                          </span>
                          <span className="leading-relaxed">{unc}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="text-xs text-dark-500 italic">No significant uncertainties noted.</p>
                  )}
                </div>
              </div>
            )}

            {/* Confidence Breakdown for Leading Hypothesis */}
            <ConfidenceBreakdownView
              breakdown={
                agentReport?.deterministicConfidenceBreakdown ||
                hypotheses[0]?.confidenceBreakdown ||
                deterministicInfo?.topHypothesis?.confidenceBreakdown
              }
              overallScore={
                hypotheses[0]?.confidenceScore ||
                deterministicInfo?.topHypothesis?.confidenceScore ||
                0
              }
            />
          </div>
        )}

        {/* Tab 2: Hypotheses */}
        {activeTab === 'hypotheses' && (
          <div className="space-y-6">
            <HypothesesList hypotheses={hypotheses} />
            <ConfidenceBreakdownView
              breakdown={
                hypotheses[0]?.confidenceBreakdown ||
                agentReport?.deterministicConfidenceBreakdown
              }
              overallScore={hypotheses[0]?.confidenceScore || 0}
            />
          </div>
        )}

        {/* Tab 3: Claim Validation */}
        {activeTab === 'claims' && (
          <div className="space-y-6">
            <ClaimValidationTable claims={claims} />
          </div>
        )}

        {/* Tab 4: Evidence Explorer */}
        {activeTab === 'evidence' && (
          <div className="space-y-6">
            <EvidenceExplorer evidenceItems={evidenceItems} />
          </div>
        )}

        {/* Tab 5: Timeline */}
        {activeTab === 'timeline' && (
          <div className="space-y-6">
            <ChronologicalTimeline events={timelineEvents} />
          </div>
        )}

        {/* Tab 6: External Technical Research */}
        {activeTab === 'research' && (
          <div className="space-y-6">
            <ExternalResearchList
              findings={researchFindings}
              sourceUrls={agentReport?.sourceUrls}
            />
          </div>
        )}

        {/* Tab 7: Agent Audit Trail */}
        {activeTab === 'audit' && (
          <div className="space-y-6">
            <AgentAuditTrail steps={agentSteps} />
          </div>
        )}
      </div>
    </div>
  );
};
