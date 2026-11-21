import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { BrowserRouter } from 'react-router-dom';
import { ClaimValidationTable } from '../components/investigation/ClaimValidationTable';
import { EvidenceExplorer } from '../components/evidence/EvidenceExplorer';
import { RecentInvestigations } from '../components/dashboard/RecentInvestigations';
import {
  buildAuthoritativeInvestigations,
  calculateVerdictMetrics,
} from '../utils/verdictAggregation';
import type {
  ConfidenceBreakdown,
  EvidenceItem,
  InvestigationAgentReport,
  InvestigationClaim,
  InvestigationResponse,
} from '../api/types';

describe('Phase 9.6.3 — Frontend Evidence Navigation & Verdict Consistency', () => {
  const mockBreakdown: ConfidenceBreakdown = {
    temporalPoints: 0.3,
    overlapPoints: 0.3,
    agreementPoints: 0.25,
    contradictionPenalty: 0.0,
    rawScore: 0.85,
    normalizedScore: 0.85,
    scoreReasons: ['Temporal proximity', 'Stack trace overlap'],
    penaltyReasons: [],
  };

  const sampleEvidence: EvidenceItem[] = [
    {
      evidenceId: 'EVD-GIT-616947b',
      evidenceType: 'COMMIT',
      source: 'GIT',
      originalReference: '616947b',
      timestamp: '2026-06-29T16:16:45Z',
      summary: 'Initial commit: TacitIQ Industrial Document Intelligence Platform',
      metadata: { author: 'sasidaran-99', commitHash: '616947b' },
      integrityHash: 'hash-616947b',
    },
    {
      evidenceId: 'EVD-WT-MISSING-01',
      evidenceType: 'WORKING_TREE_CHANGE',
      source: 'WORKING_TREE',
      originalReference: 'backend/src/main/java/com/tacitiq/TacitIqApplication.java',
      timestamp: '2026-06-30T10:00:00Z',
      summary: 'Working tree file missing from disk: TacitIqApplication.java',
      metadata: { filePath: 'TacitIqApplication.java' },
      integrityHash: 'hash-wt-01',
    },
  ];

  const sampleClaim: InvestigationClaim = {
    claimId: 'CLM-WT-001',
    investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
    statement: 'Tracked files were deleted from working tree without commit',
    status: 'SUPPORTED',
    supportingEvidenceIds: ['EVD-GIT-616947b', 'EVD-WT-MISSING-01'],
    contradictingEvidenceIds: ['EVD-CONTRA-99'],
    explanation: 'Confirmed working tree anomaly',
    assertedAt: '2026-06-30T10:00:00Z',
  };

  // Requirement 1: Clicking a supporting evidence ID navigates to Evidence and searches that ID
  it('navigates and searches evidence when supporting evidence ID is clicked', () => {
    const onSelect = vi.fn();
    render(<ClaimValidationTable claims={[sampleClaim]} onSelectEvidence={onSelect} />);

    const btn = screen.getByRole('button', { name: /View supporting evidence EVD-GIT-616947b/i });
    fireEvent.click(btn);
    expect(onSelect).toHaveBeenCalledWith('EVD-GIT-616947b');

    // Render EvidenceExplorer with selectedEvidenceId
    render(
      <EvidenceExplorer
        evidenceItems={sampleEvidence}
        highlightedEvidenceId="EVD-GIT-616947b"
      />
    );

    // Search input should be automatically populated
    const searchInput = screen.getByPlaceholderText(/Search evidence by ID/i) as HTMLInputElement;
    expect(searchInput.value).toBe('EVD-GIT-616947b');

    // Corresponding item is rendered and highlighted
    expect(screen.getByText('SELECTED FROM CLAIM')).toBeInTheDocument();
    expect(
      screen.getByText('Initial commit: TacitIQ Industrial Document Intelligence Platform')
    ).toBeInTheDocument();
  });

  // Requirement 2: Clicking a contradictory evidence ID does the same
  it('navigates and searches evidence when counter-evidence ID is clicked', () => {
    const onSelect = vi.fn();
    render(<ClaimValidationTable claims={[sampleClaim]} onSelectEvidence={onSelect} />);

    const btn = screen.getByRole('button', { name: /View counter-evidence EVD-CONTRA-99/i });
    fireEvent.click(btn);
    expect(onSelect).toHaveBeenCalledWith('EVD-CONTRA-99');
  });

  // Requirement 3: Missing evidence IDs are handled safely without crashing
  it('handles missing evidence IDs gracefully in EvidenceExplorer', () => {
    const onClear = vi.fn();
    render(
      <EvidenceExplorer
        evidenceItems={sampleEvidence}
        highlightedEvidenceId="EVD-MISSING-NONEXISTENT"
        onClearHighlight={onClear}
      />
    );

    // Shows graceful message instead of crashing
    expect(
      screen.getByText(/No evidence items match your search or filter criteria/i)
    ).toBeInTheDocument();
    expect(screen.getByText(/"EVD-MISSING-NONEXISTENT"/i)).toBeInTheDocument();

    // Allows clearing search to restore view
    const clearBtn = screen.getByRole('button', { name: /Clear search and show all evidence/i });
    fireEvent.click(clearBtn);
    expect(onClear).toHaveBeenCalled();
  });

  // Requirement 4: CONFIRMED agent report with zero hypotheses displays CONFIRMED
  it('renders CONFIRMED verdict and primary root cause when zero hypotheses exist', () => {
    const confirmedWorkingTreeReport: InvestigationAgentReport = {
      reportId: 'REP-e2803caa',
      investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
      summary: 'Automated forensic investigation into missing working tree files.',
      primarySuspectedRootCause:
        'Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history.',
      alternativeHypotheses: [],
      supportingEvidenceIds: ['EVD-WT-MISSING-01'],
      contradictoryEvidenceIds: [],
      deterministicConfidenceBreakdown: mockBreakdown,
      aiReasoningSummary: 'Confirmed working tree anomaly',
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [],
      remainingUncertainties: [],
      finalStatus: 'CONFIRMED',
      validatedClaims: [],
      investigationSteps: [],
      generatedAt: '2026-06-30T10:00:00Z',
    };

    const invWithoutHypotheses: InvestigationResponse = {
      investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
      investigationMode: 'WORKING_TREE_FORENSICS',
      serviceName: 'EconomicTimes',
      environment: 'production',
      status: 'COMPLETED',
      commitsAnalyzed: 15,
      errorsDetected: 0,
      hypothesesCount: 0,
      claimsCount: 17,
      evidenceSufficient: true,
      topHypothesis: null,
      startedAt: '2026-06-30T09:55:00Z',
      completedAt: '2026-06-30T10:00:00Z',
    };

    const items = buildAuthoritativeInvestigations(
      [invWithoutHypotheses],
      [confirmedWorkingTreeReport]
    );

    expect(items[0].verdict).toBe('CONFIRMED');
    expect(items[0].hypothesesCount).toBe(0);
    expect(items[0].primaryRootCause).toContain('Working tree filesystem anomaly');

    // Render in RecentInvestigations
    render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText(/Working tree filesystem anomaly/i)).toBeInTheDocument();
    // Does NOT say "Inconclusive / No Hypotheses"
    expect(screen.queryByText('Inconclusive / No Hypotheses')).not.toBeInTheDocument();
    // Real heuristic candidate score is rendered (85.0%), zero fabricated 95%
    expect(screen.getByText('85.0%')).toBeInTheDocument();
    expect(screen.queryByText('95%')).not.toBeInTheDocument();
  });

  // Requirement 5: PROBABLE and INSUFFICIENT_EVIDENCE verdicts display correctly
  it('correctly displays PROBABLE and INSUFFICIENT_EVIDENCE verdicts', () => {
    const probableReport: InvestigationAgentReport = {
      reportId: 'REP-prob-01',
      investigationId: 'inv-prob',
      summary: 'Probable configuration change',
      primarySuspectedRootCause: 'Database connection pool reduction',
      alternativeHypotheses: [],
      supportingEvidenceIds: [],
      contradictoryEvidenceIds: [],
      deterministicConfidenceBreakdown: mockBreakdown,
      aiReasoningSummary: 'Probable',
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [],
      remainingUncertainties: [],
      finalStatus: 'PROBABLE',
      validatedClaims: [],
      investigationSteps: [],
      generatedAt: '2026-06-30T10:00:00Z',
    };

    const insufficientReport: InvestigationAgentReport = {
      ...probableReport,
      reportId: 'REP-insuf-01',
      investigationId: 'inv-insuf',
      primarySuspectedRootCause: '',
      finalStatus: 'INSUFFICIENT_EVIDENCE',
    };

    const items = buildAuthoritativeInvestigations([], [probableReport, insufficientReport]);
    expect(items.find((i) => i.investigationId === 'inv-prob')?.verdict).toBe('PROBABLE');
    expect(items.find((i) => i.investigationId === 'inv-insuf')?.verdict).toBe(
      'INSUFFICIENT_EVIDENCE'
    );

    render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('PROBABLE')).toBeInTheDocument();
    expect(screen.getByText('INSUFFICIENT EVIDENCE')).toBeInTheDocument();
  });

  // Requirement 6: Investigation without an agent report does not invent a verdict
  it('does not invent a verdict when no agent report exists', () => {
    const pendingInv: InvestigationResponse = {
      investigationId: 'pending-inv',
      investigationMode: 'RUNTIME_FAILURE',
      serviceName: 'OrderService',
      environment: 'staging',
      status: 'COMPLETED',
      commitsAnalyzed: 5,
      errorsDetected: 1,
      hypothesesCount: 1,
      claimsCount: 2,
      evidenceSufficient: true,
      topHypothesis: {
        id: 'hypo-1',
        rank: 1,
        status: 'PROPOSED',
        confidenceScore: 0.72,
        summary: 'Timeout adjustment in network layer',
        evidenceIds: ['evd-1'],
        contradictingEvidenceIds: [],
        matchedRules: ['RULE_TIMEOUT'],
        ruleMatchReasons: {},
        penaltyReasons: [],
        confidenceBreakdown: mockBreakdown,
      },
      startedAt: '2026-06-30T09:00:00Z',
      completedAt: '2026-06-30T09:05:00Z',
    };

    const items = buildAuthoritativeInvestigations([pendingInv], []);
    expect(items[0].verdict).toBe('REPORT_PENDING');
    expect(items[0].hasAgentReport).toBe(false);

    render(
      <BrowserRouter>
        <RecentInvestigations authoritativeItems={items} />
      </BrowserRouter>
    );

    expect(screen.getByText('REPORT PENDING')).toBeInTheDocument();
    expect(screen.getByText('Timeout adjustment in network layer')).toBeInTheDocument();
    // Uses heuristic score
    expect(screen.getByText('72.0%')).toBeInTheDocument();
  });

  // Requirement 7: Existing History and dossier verdicts remain consistent
  it('preserves consistency across History and metrics aggregation', () => {
    const confirmedReport: InvestigationAgentReport = {
      reportId: 'REP-e2803caa',
      investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
      summary: 'Working tree forensic analysis',
      primarySuspectedRootCause: 'Working tree filesystem anomaly',
      alternativeHypotheses: [],
      supportingEvidenceIds: [],
      contradictoryEvidenceIds: [],
      deterministicConfidenceBreakdown: mockBreakdown,
      aiReasoningSummary: 'Confirmed',
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [],
      remainingUncertainties: [],
      finalStatus: 'CONFIRMED',
      validatedClaims: [],
      investigationSteps: [],
      generatedAt: '2026-06-30T10:00:00Z',
    };

    const items = buildAuthoritativeInvestigations([], [confirmedReport]);
    const metrics = calculateVerdictMetrics(items);

    expect(metrics.totalAnalyzed).toBe(1);
    expect(metrics.confirmedCount).toBe(1);
    expect(metrics.probableCount).toBe(0);
    expect(metrics.insufficientCount).toBe(0);
  });
});
