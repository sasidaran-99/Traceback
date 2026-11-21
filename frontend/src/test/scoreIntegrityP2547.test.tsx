import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BrowserRouter } from 'react-router-dom';
import { RecentInvestigations } from '../components/dashboard/RecentInvestigations';
import { HistoryPage } from '../pages/HistoryPage';
import { SummaryCard } from '../components/investigation/SummaryCard';
import { investigationApi } from '../api/investigationApi';
import { buildAuthoritativeInvestigations } from '../utils/verdictAggregation';
import type {
  AgentInvestigationStatus,
  ConfidenceBreakdown,
  InvestigationAgentReport,
  InvestigationResponse,
} from '../api/types';

describe('Phase 2.54.7 — Application-Wide Score Integrity Regression Suite', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  const validBreakdown: ConfidenceBreakdown = {
    temporalPoints: 28,
    overlapPoints: 30,
    agreementPoints: 24,
    contradictionPenalty: 0,
    rawScore: 82,
    normalizedScore: 0.824, // 82.4%
    scoreReasons: ['Temporal proximity', 'Stack trace overlap', 'Git agreement'],
    penaltyReasons: [],
  };

  const createInv = (
    id: string,
    verdict: string,
    score?: number,
    breakdown?: ConfidenceBreakdown
  ): { inv: InvestigationResponse; report: InvestigationAgentReport } => {
    const inv: InvestigationResponse = {
      investigationId: id,
      serviceName: 'order-service',
      environment: 'production',
      investigationMode: 'RUNTIME_FAILURE',
      commitsAnalyzed: 10,
      errorsDetected: 2,
      hypothesesCount: score !== undefined ? 1 : 0,
      claimsCount: 2,
      evidenceSufficient: verdict !== 'INSUFFICIENT_EVIDENCE',
      status: verdict === 'CONFIRMED' ? 'COMPLETED' : verdict,
      startedAt: '2026-11-18T10:00:00Z',
      completedAt: '2026-11-18T10:05:00Z',
      topHypothesis:
        score !== undefined
          ? {
              id: `hypo-${id}`,
              rank: 1,
              summary: 'Suspect commit connection leak',
              confidenceScore: score,
              confidenceBreakdown: breakdown,
              status: verdict === 'CONFIRMED' ? 'CONFIRMED' : 'PROPOSED',
              evidenceIds: ['EVD-1'],
              contradictingEvidenceIds: [],
              matchedRules: [],
              ruleMatchReasons: {},
              penaltyReasons: [],
            }
          : undefined,
    };

    const report: InvestigationAgentReport = {
      reportId: `REP-${id}`,
      investigationId: id,
      summary: `Investigation completed with verdict ${verdict}`,
      primarySuspectedRootCause: 'Suspect commit connection leak',
      alternativeHypotheses: [],
      supportingEvidenceIds: ['EVD-1'],
      contradictoryEvidenceIds: [],
      deterministicConfidenceBreakdown: breakdown,
      aiReasoningSummary: 'Deterministic correlation analysis completed.',
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [],
      remainingUncertainties: [],
      finalStatus: verdict as AgentInvestigationStatus,
      validatedClaims: [],
      investigationSteps: [],
      generatedAt: '2026-11-18T10:05:00Z',
    };

    return { inv, report };
  };

  // 1. CONFIRMED with a valid score
  it('Scenario 1: CONFIRMED with a valid score renders the actual score (82.4%) on Dashboard and History', async () => {
    const { inv, report } = createInv('inv-conf-valid', 'CONFIRMED', 0.824, validBreakdown);
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard RecentInvestigations
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('82.4%')).toBeInTheDocument();
    expect(screen.queryByText('95%')).not.toBeInTheDocument();
    unmount();

    // HistoryPage
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('82.4%')).toBeInTheDocument();
    });
    expect(screen.queryByText(/95(\.0)?%/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Grounded/i)).not.toBeInTheDocument();
  });

  // 2. CONFIRMED without a score
  it('Scenario 2: CONFIRMED without a score renders Unscored (NO fabricated 95%)', async () => {
    const { inv, report } = createInv('inv-conf-unscored', 'CONFIRMED', undefined, undefined);
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard RecentInvestigations
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('Unscored')).toBeInTheDocument();
    expect(screen.queryByText('95%')).not.toBeInTheDocument();
    expect(screen.queryByText('95.0%')).not.toBeInTheDocument();
    unmount();

    // HistoryPage
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unscored')).toBeInTheDocument();
    });
    expect(screen.queryByText(/95(\.0)?%/)).not.toBeInTheDocument();
  });

  // 3. PROBABLE with a valid score
  it('Scenario 3: PROBABLE with a valid score renders the actual score (71.0%) on Dashboard and History', async () => {
    const { inv, report } = createInv('inv-prob-valid', 'PROBABLE', 0.71, {
      ...validBreakdown,
      rawScore: 71,
      normalizedScore: 0.71,
    });
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard RecentInvestigations
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('PROBABLE')).toBeInTheDocument();
    expect(screen.getByText('71.0%')).toBeInTheDocument();
    unmount();

    // HistoryPage
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('71.0%')).toBeInTheDocument();
    });
  });

  // 4. PROBABLE without a score
  it('Scenario 4: PROBABLE without a score renders Unscored (NO fabricated 71% or 75%)', async () => {
    const { inv, report } = createInv('inv-prob-unscored', 'PROBABLE', undefined, undefined);
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard RecentInvestigations
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('PROBABLE')).toBeInTheDocument();
    expect(screen.getByText('Unscored')).toBeInTheDocument();
    expect(screen.queryByText('71%')).not.toBeInTheDocument();
    expect(screen.queryByText('71.0%')).not.toBeInTheDocument();
    expect(screen.queryByText('75%')).not.toBeInTheDocument();
    unmount();

    // HistoryPage
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unscored')).toBeInTheDocument();
    });
    expect(screen.queryByText('71%')).not.toBeInTheDocument();
    expect(screen.queryByText('71.0%')).not.toBeInTheDocument();
    expect(screen.queryByText('75%')).not.toBeInTheDocument();
  });

  // 5. INSUFFICIENT_EVIDENCE without a score
  it('Scenario 5: INSUFFICIENT_EVIDENCE without a score renders Unscored', async () => {
    const { inv, report } = createInv('inv-inconcl', 'INSUFFICIENT_EVIDENCE', undefined, undefined);
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard RecentInvestigations
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('INSUFFICIENT EVIDENCE')).toBeInTheDocument();
    expect(screen.getByText('Unscored')).toBeInTheDocument();
    expect(screen.queryByText('0.0%')).not.toBeInTheDocument();
    unmount();

    // HistoryPage
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('INSUFFICIENT EVIDENCE')).toBeInTheDocument();
      expect(screen.getByText('Unscored')).toBeInTheDocument();
    });
    expect(screen.queryByText('0.0%')).not.toBeInTheDocument();
  });

  // 6. Dashboard and History displaying the same actual score for the same investigation
  it('Scenario 6: Dashboard and History display the exact same actual score (64.5%) for the same investigation', async () => {
    const scoreVal = 0.645;
    const { inv, report } = createInv('inv-cross-view', 'PROBABLE', scoreVal, {
      ...validBreakdown,
      normalizedScore: scoreVal,
    });
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );
    expect(screen.getByText('64.5%')).toBeInTheDocument();
    unmount();

    // History
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('64.5%')).toBeInTheDocument();
    });
  });

  // 7. Dashboard and History displaying Unscored when score telemetry is absent
  it('Scenario 7: Dashboard and History display Unscored when score telemetry is absent', async () => {
    const { inv, report } = createInv('inv-absent-telemetry', 'CONFIRMED', undefined, undefined);
    const items = buildAuthoritativeInvestigations([inv], [report]);

    // Dashboard
    const { unmount } = render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );
    expect(screen.getByText('Unscored')).toBeInTheDocument();
    unmount();

    // History
    vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue([inv]);
    vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue([report]);

    render(
      <BrowserRouter>
        <HistoryPage />
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unscored')).toBeInTheDocument();
    });
  });

  // 8. Verdict remaining visible independently of score availability
  it('Scenario 8: Categorical verdict badges remain visible and independent of score availability', () => {
    const { inv, report } = createInv('inv-independent-verdict', 'CONFIRMED', undefined, undefined);
    const items = buildAuthoritativeInvestigations([inv], [report]);

    render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    // Both the verdict badge and the Unscored text coexist
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('Unscored')).toBeInTheDocument();
  });

  // 9. No verdict-derived 95%, 71%, or zero score
  it('Scenario 9: Never manufactures verdict-derived 95%, 71%, or 0.0% when score is missing', () => {
    const { inv: inv1, report: rep1 } = createInv('inv-no-der-1', 'CONFIRMED', undefined, undefined);
    const { inv: inv2, report: rep2 } = createInv('inv-no-der-2', 'PROBABLE', undefined, undefined);
    const { inv: inv3, report: rep3 } = createInv('inv-no-der-3', 'INSUFFICIENT_EVIDENCE', undefined, undefined);

    const items = buildAuthoritativeInvestigations([inv1, inv2, inv3], [rep1, rep2, rep3]);

    render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.queryByText('95%')).not.toBeInTheDocument();
    expect(screen.queryByText('95.0%')).not.toBeInTheDocument();
    expect(screen.queryByText('71%')).not.toBeInTheDocument();
    expect(screen.queryByText('71.0%')).not.toBeInTheDocument();
    expect(screen.queryByText('75%')).not.toBeInTheDocument();
    expect(screen.queryByText('0.0%')).not.toBeInTheDocument();

    const unscoredEls = screen.getAllByText('Unscored');
    expect(unscoredEls.length).toBe(3);
  });

  // 10. SummaryCard no longer rendering separate legacy scores
  it('Scenario 10: SummaryCard renders single Candidate Score and no longer renders dual legacy scores', () => {
    render(
      <SummaryCard
        investigationId="inv-summary-clean"
        serviceName="payment-service"
        environment="production"
        status="CONFIRMED"
        summary="Payment failure analysis"
        createdAt="2026-11-18T10:00:00Z"
        investigationScore={0.95}
        heuristicScore={0.85}
      />
    );

    // Single candidate score rendered (resolves to 0.85 from heuristicScore alias)
    expect(screen.getByText('Candidate Score:')).toBeInTheDocument();
    expect(screen.getByText('85% (0.85)')).toBeInTheDocument();

    // Zero legacy dual-score headers
    expect(screen.queryByText('Investigation Score:')).not.toBeInTheDocument();
    expect(screen.queryByText(/Authoritative Evidence Score/i)).not.toBeInTheDocument();
    expect(screen.queryByText('95% (0.95)')).not.toBeInTheDocument();
  });
});
