import { apiClient, AGENT_INVESTIGATION_TIMEOUT_MS } from './client';
import type {
  AgentInvestigationRequest,
  AgentInvestigationStep,
  EvidenceItem,
  ExternalResearchFinding,
  HealthResponse,
  HypothesisResponse,
  InvestigationAgentReport,
  InvestigationClaim,
  InvestigationRequest,
  InvestigationResponse,
  TimelineEvent,
} from './types';

export const investigationApi = {
  /**
   * Check system health and backend connectivity.
   */
  getHealth: () => apiClient<HealthResponse>('/api/health'),

  /**
   * List all deterministic investigations.
   */
  getAllInvestigations: () => apiClient<InvestigationResponse[]>('/api/investigations'),

  /**
   * Retrieve a deterministic investigation overview by ID.
   */
  getInvestigation: (id: string) => apiClient<InvestigationResponse>(`/api/investigations/${id}`),

  /**
   * Retrieve chronological timeline events for an investigation.
   */
  getTimeline: (id: string) => apiClient<TimelineEvent[]>(`/api/investigations/${id}/timeline`),

  /**
   * Retrieve registered evidence items for an investigation.
   */
  getEvidence: (id: string) => apiClient<EvidenceItem[]>(`/api/investigations/${id}/evidence`),

  /**
   * Retrieve ranked candidate root-cause hypotheses for an investigation.
   */
  getHypotheses: (id: string) => apiClient<HypothesisResponse[]>(`/api/investigations/${id}/hypotheses`),

  /**
   * Retrieve deterministic claim validation results for an investigation.
   */
  getClaims: (id: string) => apiClient<InvestigationClaim[]>(`/api/investigations/${id}/claims`),

  /**
   * Launch a deterministic investigation.
   */
  startDeterministicInvestigation: (request: InvestigationRequest) =>
    apiClient<InvestigationResponse>('/api/investigations', {
      method: 'POST',
      body: JSON.stringify(request),
    }),

  /**
   * Launch an autonomous AI investigation (Phase 4 agent).
   * Uses an extended timeout (210s) to accommodate backend Bifrost LLM execution.
   */
  startAgentInvestigation: (request: AgentInvestigationRequest, customTimeoutMs?: number) =>
    apiClient<InvestigationAgentReport>(
      '/api/investigations/agent',
      {
        method: 'POST',
        body: JSON.stringify(request),
      },
      customTimeoutMs ?? AGENT_INVESTIGATION_TIMEOUT_MS
    ),

  /**
   * Retrieve the AI agent report for an investigation.
   */
  getAgentReport: (id: string) =>
    apiClient<InvestigationAgentReport>(`/api/investigations/${id}/agent-report`),

  /**
   * Retrieve external technical research findings collected by Tavily.
   */
  getResearchFindings: (id: string) =>
    apiClient<ExternalResearchFinding[]>(`/api/investigations/${id}/research`),

  /**
   * Retrieve sequential audit steps executed by the agent.
   */
  getAgentSteps: (id: string) =>
    apiClient<AgentInvestigationStep[]>(`/api/investigations/${id}/agent-steps`),

  /**
   * Retrieve all completed autonomous AI agent reports.
   */
  getAllAgentReports: () =>
    apiClient<InvestigationAgentReport[]>('/api/investigations/agent'),
};
