import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ContextualInspector } from '../components/investigation/ContextualInspector';
import { InvestigationDetailPage } from '../pages/InvestigationDetailPage';
import { HypothesesList } from '../components/investigation/HypothesesList';
import { SummaryCard } from '../components/investigation/SummaryCard';
import { investigationApi } from '../api/investigationApi';
import type {
  InvestigationResponse,
  InvestigationAgentReport,
  HypothesisResponse,
  InvestigationClaim,
  EvidenceItem,
  TimelineEvent,
} from '../api/types';

describe('Phase 2.54.5 — Evidence Score Integrity & Semantic Correction', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  const createMockInv = (
    status: string,
    hasBreakdown: boolean,
    confidenceScore?: number
  ): InvestigationResponse => ({
    investigationId: 'inv-score-audit-1',
    investigationMode: 'RUNTIME_FAILURE',
    serviceName: 'payment-service',
    environment: 'production',
    status,
    commitsAnalyzed: 10,
    errorsDetected: 2,
    hypothesesCount: 1,
    claimsCount: 2,
    evidenceSufficient: status !== 'INSUFFICIENT_EVIDENCE',
    topHypothesis: {
      hypothesisId: 'hyp-1',
      investigationId: 'inv-score-audit-1',
      rank: 1,
      commitSha: 'a1b2c3d4e5f6',
      status: status === 'CONFIRMED' ? 'CONFIRMED' : 'PROPOSED',
      confidenceScore: confidenceScore ?? (hasBreakdown ? 0.85 : 0.0),
      summary: 'Connection pool timeout in DatabaseConnector.java',
      supportingEvidenceIds: ['EVD-1'],
      counterEvidence: '',
      hasStructuralOverlap: true,
      hasConfigOverlap: false,
      breakdown: hasBreakdown
        ? {
            temporalPoints: 30,
            overlapPoints: 30,
            agreementPoints: 10,
            contradictionPenalty: 0,
            totalRawScore: 70,
            normalizedScore: 0.824,
            scoreReasons: ['Temporal proximity bonus', 'Class frame match'],
            penaltyReasons: [],
          }
        : undefined,
    },
    startedAt: '2026-11-18T10:00:00Z',
    completedAt: '2026-11-18T10:00:05Z',
  });

  const createMockReport = (
    finalStatus: 'CONFIRMED' | 'PROBABLE' | 'INSUFFICIENT_EVIDENCE',
    hasBreakdown: boolean,
    supportingEvidence: string[] = ['EVD-1']
  ): InvestigationAgentReport => ({
    reportId: 'REP-score-audit-1',
    investigationId: 'inv-score-audit-1',
    summary: `Investigation completed with status ${finalStatus}`,
    primarySuspectedRootCause: 'Database connection starvation in commit a1b2c3d4',
    alternativeHypotheses: [],
    supportingEvidenceIds: supportingEvidence,
    contradictoryEvidenceIds: [],
    aiReasoningSummary: 'Deterministic correlation completed.',
    externalResearchFindings: [],
    sourceUrls: [],
    recommendedVerificationSteps: ['Verify database pool size'],
    remainingUncertainties: [],
    finalStatus,
    deterministicConfidenceBreakdown: hasBreakdown
      ? {
          temporalPoints: 30,
          overlapPoints: 30,
          agreementPoints: 10,
          contradictionPenalty: 0,
          rawScore: 70,
          normalizedScore: 0.824,
          scoreReasons: ['Temporal match', 'Stack match'],
          penaltyReasons: [],
        }
      : undefined,
  });

  const mockClaims: InvestigationClaim[] = [
    {
      claimId: 'CLM-1',
      investigationId: 'inv-score-audit-1',
      statement: 'Database connection pool size was reduced',
      status: 'SUPPORTED',
      explanation: 'Verified via commit diff evidence',
      supportingEvidenceIds: ['EVD-1'],
      contradictingEvidenceIds: [],
      assertedAt: '2026-11-18T10:00:01Z',
    },
  ];

  const mockEvidence: EvidenceItem[] = [
    {
      evidenceId: 'EVD-1',
      evidenceType: 'CONFIG_DIFF',
      source: 'GIT',
      originalReference: 'pool.maxSize = 2',
      timestamp: '2026-11-18T09:45:00Z',
      summary: 'HikariCP configuration pool size reduced to 2',
      integrityHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    },
  ];

  const mockTimeline: TimelineEvent[] = [
    {
      eventId: 'EVT-1',
      timestamp: '2026-11-18T09:45:00Z',
      eventType: 'CONFIG_DIFF',
      source: 'GIT',
      summary: 'Commit a1b2c3d4 modified pool size',
      isPreFailure: true,
      relatedEvidenceId: 'EVD-1',
    },
  ];

  // 1. CONFIRMED status with a valid breakdown
  it('Requirement 1: renders genuine Candidate Score and breakdown when CONFIRMED has valid breakdown', async () => {
    const inv = createMockInv('CONFIRMED', true);
    const rep = createMockReport('CONFIRMED', true);

    vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(inv);
    vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(rep);
    vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([inv.topHypothesis!]);
    vi.spyOn(investigationApi, 'getClaims').mockResolvedValue(mockClaims);
    vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue(mockEvidence);
    vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue(mockTimeline);
    vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/investigations/inv-score-audit-1']}>
        <Routes>
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    // Verify Sticky Strip Candidate Score matches breakdown (82% from 0.824)
    await waitFor(() => {
      expect(screen.getByText('Candidate Score:')).toBeInTheDocument();
      expect(screen.getByText('82%')).toBeInTheDocument();
    });

    // Verify Contextual Inspector Grounding Overview renders Heuristic Candidate Score
    expect(screen.getByText('Heuristic Candidate Score')).toBeInTheDocument();
    expect(screen.getByText(/Formula: S = T \+ O \+ A - C \(Prioritization only\)/i)).toBeInTheDocument();
    expect(screen.getByText('82% (0.82)')).toBeInTheDocument();

    // Verify misleading label is NOT present
    expect(screen.queryByText(/Authoritative Evidence Score/i)).not.toBeInTheDocument();
  });

  // 2. CONFIRMED status with a missing breakdown (displays Unscored, no fabricated 95%)
  it('Requirement 2: displays Unscored when CONFIRMED has missing breakdown (NO fabricated 95%)', async () => {
    const inv = createMockInv('CONFIRMED', false);
    // Remove topHypothesis confidenceScore so no breakdown exists
    inv.topHypothesis = undefined;
    const rep = createMockReport('CONFIRMED', false);

    vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(inv);
    vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(rep);
    vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getClaims').mockResolvedValue(mockClaims);
    vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue(mockEvidence);
    vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue(mockTimeline);
    vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/investigations/inv-score-audit-1']}>
        <Routes>
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText(/Unscored/i).length).toBeGreaterThanOrEqual(1);
    });

    // Zero fabricated 95%
    expect(screen.queryByText('95%')).not.toBeInTheDocument();
    expect(screen.queryByText('95% (0.95)')).not.toBeInTheDocument();

    // Preserve investigation verdict independently
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
  });

  // 3. PROBABLE status with a missing breakdown (displays Unscored, no fabricated 75%)
  it('Requirement 3: displays Unscored when PROBABLE has missing breakdown (NO fabricated 75%)', async () => {
    const inv = createMockInv('PROBABLE', false);
    inv.topHypothesis = undefined;
    const rep = createMockReport('PROBABLE', false);

    vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(inv);
    vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(rep);
    vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getClaims').mockResolvedValue(mockClaims);
    vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue(mockEvidence);
    vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue(mockTimeline);
    vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/investigations/inv-score-audit-1']}>
        <Routes>
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText(/Unscored/i).length).toBeGreaterThanOrEqual(1);
    });

    // Zero fabricated 75%
    expect(screen.queryByText('75%')).not.toBeInTheDocument();
    expect(screen.queryByText('75% (0.75)')).not.toBeInTheDocument();

    // Preserve investigation verdict independently
    expect(screen.getByText('PROBABLE')).toBeInTheDocument();
  });

  // 4. INSUFFICIENT_EVIDENCE status with a missing breakdown
  it('Requirement 4: displays Telemetry Insufficient when INSUFFICIENT_EVIDENCE has missing breakdown', async () => {
    const inv = createMockInv('INSUFFICIENT_EVIDENCE', false);
    inv.topHypothesis = undefined;
    const rep = createMockReport('INSUFFICIENT_EVIDENCE', false, []);

    vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(inv);
    vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(rep);
    vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/investigations/inv-score-audit-1']}>
        <Routes>
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText(/Telemetry Insufficient/i).length).toBeGreaterThanOrEqual(1);
    });

    // Zero manufactured percentages or zeroes
    expect(screen.queryByText('95%')).not.toBeInTheDocument();
    expect(screen.queryByText('75%')).not.toBeInTheDocument();

    // Preserve verdict
    expect(screen.getByText(/INSUFFICIENT EVIDENCE/i)).toBeInTheDocument();
  });

  // 5. A valid heuristic score with missing evidence score data
  it('Requirement 5: displays valid heuristic candidate score in ContextualInspector without error', () => {
    render(
      <ContextualInspector
        selection={null}
        verdict="PROBABLE"
        candidateScore={0.71}
        supportingCount={2}
        contradictoryCount={0}
        onClearSelection={vi.fn()}
      />
    );

    expect(screen.getByText('Heuristic Candidate Score')).toBeInTheDocument();
    expect(screen.getByText('71% (0.71)')).toBeInTheDocument();
    expect(screen.queryByText(/Authoritative Evidence Score/i)).not.toBeInTheDocument();
  });

  // 6. Missing individual T/O/A/C factors in Comparison Matrix
  it('Requirement 6: displays honest em-dash (—) for missing factors and never fabricates zeroes', () => {
    const hypoWithMissingFactors: HypothesisResponse = {
      hypothesisId: 'hyp-sparse-1',
      rank: 1,
      summary: 'Sparse hypothesis without overlap telemetry',
      status: 'PROPOSED',
      confidenceScore: 0.35,
      supportingEvidenceIds: ['EVD-1'],
      breakdown: {
        temporalPoints: 20,
        // overlapPoints missing (undefined)
        // agreementPoints missing (undefined)
        contradictionPenalty: 0,
      } as any,
    };

    render(
      <HypothesesList
        hypotheses={[hypoWithMissingFactors]}
        onSelectEvidence={vi.fn()}
      />
    );

    // Switch to Comparison Matrix
    const matrixBtn = screen.getByRole('button', { name: /Comparison Matrix/i });
    fireEvent.click(matrixBtn);

    // Factor T is present: +20
    expect(screen.getByText('+20')).toBeInTheDocument();
    // Factor C is present: -0
    expect(screen.getByText('-0')).toBeInTheDocument();

    // Factors O and A are missing: rendered as em-dash '—'
    const emDashes = screen.getAllByText('—');
    expect(emDashes.length).toBeGreaterThanOrEqual(2);
  });

  // 7. Consistency between the sticky strip and inspector
  it('Requirement 7: ensures sticky strip and contextual inspector report identical score values', async () => {
    const inv = createMockInv('CONFIRMED', true);
    const rep = createMockReport('CONFIRMED', true);

    vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(inv);
    vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(rep);
    vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([inv.topHypothesis!]);
    vi.spyOn(investigationApi, 'getClaims').mockResolvedValue(mockClaims);
    vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue(mockEvidence);
    vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue(mockTimeline);
    vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
    vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/investigations/inv-score-audit-1']}>
        <Routes>
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    // Sticky strip has 82%
    await waitFor(() => {
      expect(screen.getByText('82%')).toBeInTheDocument();
    });

    // Inspector also has 82% (0.82)
    expect(screen.getByText('82% (0.82)')).toBeInTheDocument();
  });

  // 8. Correct labels and tooltips
  it('Requirement 8: renders honest formula labels and disclaimer tooltips', () => {
    render(
      <ContextualInspector
        selection={null}
        verdict="CONFIRMED"
        candidateScore={0.88}
        supportingCount={5}
        contradictoryCount={0}
        onClearSelection={vi.fn()}
      />
    );

    // Label: Heuristic Candidate Score
    expect(screen.getByText('Heuristic Candidate Score')).toBeInTheDocument();
    expect(screen.getByText('Formula: S = T + O + A - C (Prioritization only)')).toBeInTheDocument();

    // Tooltip disclaims statistical probability
    const scoreElement = screen.getByTitle(
      'Deterministic ranking score based on heuristic formula (S = T + O + A - C, not a statistical probability)'
    );
    expect(scoreElement).toBeInTheDocument();
  });

  // 9. No fabricated 95% or 75% values in SummaryCard
  it('Requirement 9: SummaryCard renders Unscored when scores are missing, with zero fabricated 95% or 75%', () => {
    render(
      <SummaryCard
        investigationId="inv-summary-test-1"
        serviceName="payment-service"
        environment="production"
        status="CONFIRMED"
        summary="Test investigation summary"
        createdAt="2026-11-18T10:00:00Z"
      />
    );

    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('Candidate Score:')).toBeInTheDocument();
    expect(screen.getByText('Unscored')).toBeInTheDocument();

    // Zero 95% or 75%
    expect(screen.queryByText(/95%/)).not.toBeInTheDocument();
    expect(screen.queryByText(/75%/)).not.toBeInTheDocument();
  });

  // 10. Existing verdict behavior remains unchanged
  it('Requirement 10: preserves all investigation verdict badges and styling independently of score', () => {
    const { rerender } = render(
      <ContextualInspector
        selection={null}
        verdict="CONFIRMED"
        supportingCount={3}
        contradictoryCount={0}
        onClearSelection={vi.fn()}
      />
    );
    expect(screen.getByText('Grounding Verdict: CONFIRMED')).toBeInTheDocument();

    rerender(
      <ContextualInspector
        selection={null}
        verdict="PROBABLE"
        supportingCount={1}
        contradictoryCount={0}
        onClearSelection={vi.fn()}
      />
    );
    expect(screen.getByText('Grounding Verdict: PROBABLE')).toBeInTheDocument();

    rerender(
      <ContextualInspector
        selection={null}
        verdict="INSUFFICIENT_EVIDENCE"
        supportingCount={0}
        contradictoryCount={0}
        onClearSelection={vi.fn()}
      />
    );
    expect(screen.getByText('Grounding Verdict: INSUFFICIENT_EVIDENCE')).toBeInTheDocument();
  });
});
