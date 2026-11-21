import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { InvestigationDetailPage } from '../pages/InvestigationDetailPage';
import { investigationApi } from '../api/investigationApi';
import type {
  InvestigationResponse,
  InvestigationAgentReport,
  InvestigationHypothesis,
  InvestigationClaim,
  EvidenceItem,
  TimelineEvent,
  AgentStep,
  ExternalResearchFinding,
} from '../api/types';

describe('Phase 2.36 — Investigation Dossier Tab Navigation & Content', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  const mockInv: InvestigationResponse = {
    investigationId: 'inv-tab-audit-1',
    investigationMode: 'RUNTIME_FAILURE',
    serviceName: 'order-service',
    environment: 'production',
    status: 'COMPLETED',
    commitsAnalyzed: 5,
    errorsDetected: 1,
    hypothesesCount: 1,
    claimsCount: 2,
    evidenceSufficient: true,
    topHypothesis: {
      hypothesisId: 'hyp-1',
      investigationId: 'inv-tab-audit-1',
      rank: 1,
      commitSha: 'c1a89b3',
      status: 'CONFIRMED',
      confidenceScore: 0.85,
      summary: 'Code regression in commit c1a89b3',
      supportingEvidenceIds: ['EVD-1'],
      counterEvidence: '',
      hasStructuralOverlap: true,
      hasConfigOverlap: false,
      breakdown: {
        temporalPoints: 30,
        overlapPoints: 30,
        agreementPoints: 10,
        contradictionPenalty: 0,
        totalRawScore: 70,
        normalizedScore: 0.824,
        scoreReasons: ['Within window', 'Stack trace frame match', 'Message corroboration'],
        penaltyReasons: [],
      },
    },
    startedAt: '2026-11-15T10:20:00Z',
    completedAt: '2026-11-15T10:20:05Z',
  };

  const mockReport: InvestigationAgentReport = {
    reportId: 'REP-tab-audit-1',
    investigationId: 'inv-tab-audit-1',
    summary: 'Executive Summary of Order Failure Investigation',
    primarySuspectedRootCause: 'Null pointer exception in OrderProcessor.java line 42',
    alternativeHypotheses: ['Secondary network hypothesis'],
    supportingEvidenceIds: ['EVD-1', 'EVD-2'],
    contradictoryEvidenceIds: [],
    aiReasoningSummary: 'Deterministic reasoning confirmed commit c1a89b3 introduced regression.',
    externalResearchFindings: ['Verified against standard library docs.'],
    sourceUrls: ['https://docs.oracle.com/en/java'],
    recommendedVerificationSteps: ['Run unit tests', 'Verify deployment'],
    remainingUncertainties: [],
    finalStatus: 'CONFIRMED',
  };

  const mockHypotheses: InvestigationHypothesis[] = [
    {
      hypothesisId: 'hyp-1',
      investigationId: 'inv-tab-audit-1',
      rank: 1,
      commitSha: 'c1a89b3',
      status: 'CONFIRMED',
      confidenceScore: 0.85,
      summary: 'Code regression in commit c1a89b3',
      supportingEvidenceIds: ['EVD-1'],
      counterEvidence: '',
      hasStructuralOverlap: true,
      hasConfigOverlap: false,
    },
  ];

  const mockClaims: InvestigationClaim[] = [
    {
      claimId: 'claim-1',
      investigationId: 'inv-tab-audit-1',
      statement: 'Commit c1a89b3 modified OrderProcessor.java',
      status: 'SUPPORTED',
      explanation: 'Verified via Git diff evidence',
      supportingEvidenceIds: ['EVD-1'],
      contradictingEvidenceIds: [],
      assertedAt: '2026-11-15T10:20:00Z',
    },
    {
      claimId: 'claim-2',
      investigationId: 'inv-tab-audit-1',
      statement: 'Database pool size was reduced to 3s',
      status: 'VALIDATION_ERROR',
      explanation: 'No configuration evidence found in commit diff',
      supportingEvidenceIds: [],
      contradictingEvidenceIds: [],
      assertedAt: '2026-11-15T10:20:00Z',
    },
  ];

  const mockEvidence: EvidenceItem[] = [
    {
      evidenceId: 'EVD-1',
      evidenceType: 'COMMIT',
      source: 'GIT',
      originalReference: 'c1a89b3',
      timestamp: '2026-11-15T10:15:00Z',
      summary: 'Commit c1a89b3: Modify OrderProcessor.java',
      metadata: { author: 'Alice', files: 1 },
      integrityHash: 'a1b2c3d4e5f60000111122223333444455556666777788889999aaaabbbbcccc',
    },
  ];

  const mockTimeline: TimelineEvent[] = [
    {
      eventId: 'evt-1',
      timestamp: '2026-11-15T10:15:00Z',
      eventType: 'COMMIT',
      source: 'GIT',
      summary: 'Commit c1a89b3: Modify OrderProcessor.java',
      associatedCommitSha: 'c1a89b3',
      relatedEvidenceId: 'EVD-1',
    },
  ];

  const mockAgentSteps: AgentStep[] = [
    {
      stepId: 'step-1',
      stepNumber: 1,
      stepType: 'COLLECT_GIT_HISTORY',
      description: 'Collected 5 commits from git repository',
      status: 'COMPLETED',
      timestamp: '2026-11-15T10:20:01Z',
    },
  ];

  const mockResearch: ExternalResearchFinding[] = [
    {
      findingId: 'RES-1',
      investigationId: 'inv-tab-audit-1',
      query: 'Java NullPointerException',
      title: 'Java NPE Docs',
      url: 'https://docs.oracle.com/en/java',
      snippet: 'Verified against standard library docs.',
      publishedDate: '2026-01-01',
      author: 'Oracle',
    },
  ];

  it('renders all 7 investigation dossier tabs and switches between them cleanly', async () => {
    vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(mockInv);
    vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(mockReport);
    vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue(mockHypotheses);
    vi.spyOn(investigationApi, 'getClaims').mockResolvedValue(mockClaims);
    vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue(mockEvidence);
    vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue(mockTimeline);
    vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue(mockResearch);
    vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue(mockAgentSteps);

    render(
      <MemoryRouter initialEntries={['/investigations/inv-tab-audit-1']}>
        <Routes>
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('tablist', { name: /Investigation Dossier Tabs/i })).toBeInTheDocument();
    });

    const tabs = screen.getAllByRole('tab');
    expect(tabs.length).toBe(8);

    // Tab 1: Overview
    const overviewTab = tabs.find((t) => t.textContent?.includes('AI Report & Overview'))!;
    expect(overviewTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText(/Executive Summary of Order Failure Investigation/i)).toBeInTheDocument();

    // Tab 2: Hypotheses
    const hypTab = tabs.find((t) => t.textContent?.includes('Hypotheses (1)'))!;
    fireEvent.click(hypTab);
    expect(hypTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText(/Code regression in commit c1a89b3/i)).toBeInTheDocument();

    // Tab 3: Claims
    const claimsTab = tabs.find((t) => t.textContent?.includes('Claims (2)'))!;
    fireEvent.click(claimsTab);
    expect(claimsTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText(/Commit c1a89b3 modified OrderProcessor.java/i)).toBeInTheDocument();
    // Validate semantic status badges
    expect(screen.getByText('SUPPORTED')).toBeInTheDocument();
    expect(screen.getByText('VALIDATION ERROR')).toBeInTheDocument();

    // Tab 4: Evidence
    const evidenceTab = tabs.find((t) => t.textContent?.includes('Evidence (1)'))!;
    fireEvent.click(evidenceTab);
    expect(evidenceTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText(/EVD-1/i)).toBeInTheDocument();

    // Tab 5: Timeline
    const timelineTab = tabs.find((t) => t.textContent?.includes('Timeline (1)'))!;
    fireEvent.click(timelineTab);
    expect(timelineTab.getAttribute('aria-selected')).toBe('true');

    // Tab 6: Research
    const researchTab = tabs.find((t) => t.textContent?.includes('Research (1)'))!;
    fireEvent.click(researchTab);
    expect(researchTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText(/Verified against standard library docs/i)).toBeInTheDocument();

    // Tab 7: Agent Steps
    const auditTab = tabs.find((t) => t.textContent?.includes('Agent Steps (1)'))!;
    fireEvent.click(auditTab);
    expect(auditTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText(/Collected 5 commits from git repository/i)).toBeInTheDocument();
  });
});
