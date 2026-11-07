import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import {
  buildAuthoritativeInvestigations,
  calculateVerdictMetrics,
} from '../utils/verdictAggregation';
import { StatsOverview } from '../components/dashboard/StatsOverview';
import { RecentInvestigations } from '../components/dashboard/RecentInvestigations';
import type { InvestigationAgentReport, InvestigationResponse } from '../api/types';

describe('Verdict Aggregation & Dashboard Metric Consistency', () => {
  const probableInvestigation: InvestigationResponse = {
    investigationId: '9ffbdd7d-9992-4409-a869-d4792ca3002a',
    serviceName: 'payment-service',
    environment: 'production',
    status: 'COMPLETED',
    commitsAnalyzed: 2,
    errorsDetected: 1,
    hypothesesCount: 2,
    claimsCount: 2,
    evidenceSufficient: true,
    topHypothesis: {
      id: 'hypo-1',
      rank: 1,
      confidenceScore: 0.71,
      status: 'PROPOSED',
      summary: 'Potential contributing change in commit 3f38e55: reduced HikariCP pool size',
      verificationPlan: 'Verify pool config',
      counterEvidence: 'None',
      evidenceIds: ['EVD-GIT-3f38e55'],
      contradictingEvidenceIds: [],
      matchedRules: ['RULE_C_CONFIG_OVERLAP'],
      ruleMatchReasons: {},
      penaltyReasons: [],
      rankingExplanation: 'Strong overlap',
    },
    startedAt: '2026-11-07T10:29:13Z',
    completedAt: '2026-11-07T10:29:14Z',
  };

  const probableAgentReport: InvestigationAgentReport = {
    reportId: 'REP-724858a7',
    investigationId: '9ffbdd7d-9992-4409-a869-d4792ca3002a',
    summary: 'Investigation identified commit 3f38e55 as a probable contributing change.',
    primarySuspectedRootCause: 'Potential contributing change in commit 3f38e55',
    alternativeHypotheses: [],
    recommendedVerificationSteps: [],
    remainingUncertainties: ['Active deployment unconfirmed'],
    aiReasoningSummary: 'Deterministic Fallback Analysis',
    externalResearchFindings: [],
    supportingEvidenceIds: ['EVD-GIT-3f38e55'],
    contradictoryEvidenceIds: [],
    sourceUrls: [],
    validatedClaims: [],
    investigationSteps: [],
    finalStatus: 'PROBABLE',
    generatedAt: '2026-11-07T10:29:24Z',
  };

  const insufficientInvestigation: InvestigationResponse = {
    investigationId: '7124c213-b473-4e42-b5cf-faf38d66d035',
    serviceName: 'payment-service',
    environment: 'production',
    status: 'INSUFFICIENT_EVIDENCE',
    commitsAnalyzed: 2,
    errorsDetected: 1,
    hypothesesCount: 3,
    claimsCount: 2,
    evidenceSufficient: false,
    topHypothesis: {
      id: 'hypo-2',
      rank: 1,
      confidenceScore: 0.0,
      status: 'REJECTED',
      summary: 'INSUFFICIENT_EVIDENCE: Available telemetry does not contain matching changes.',
      verificationPlan: 'Expand window',
      counterEvidence: 'Zero overlap',
      evidenceIds: [],
      contradictingEvidenceIds: [],
      matchedRules: [],
      ruleMatchReasons: {},
      penaltyReasons: ['Zero overlap'],
      rankingExplanation: 'Abstained',
    },
    startedAt: '2026-11-07T10:27:14Z',
    completedAt: '2026-11-07T10:27:15Z',
  };

  it('aggregates current live scenario: 1 PROBABLE and 1 INSUFFICIENT_EVIDENCE', () => {
    const rawInvs = [probableInvestigation, insufficientInvestigation];
    const rawReports = [probableAgentReport]; // Only 1 report exists; 2nd has no agent report

    const items = buildAuthoritativeInvestigations(rawInvs, rawReports);
    expect(items).toHaveLength(2);

    const metrics = calculateVerdictMetrics(items);

    // Assert exact counts
    expect(metrics.totalAnalyzed).toBe(2);
    expect(metrics.confirmedCount).toBe(0);
    expect(metrics.probableCount).toBe(1);
    expect(metrics.insufficientCount).toBe(1);
    expect(metrics.failedCount).toBe(0);
    expect(metrics.pendingReportCount).toBe(0);
    expect(metrics.totalCommits).toBe(4);
    expect(metrics.totalErrors).toBe(2);
    expect(metrics.totalHypotheses).toBe(5);
  });

  it('strictly counts CONFIRMED only when verdict is CONFIRMED', () => {
    const confirmedReport: InvestigationAgentReport = {
      ...probableAgentReport,
      reportId: 'REP-conf-1',
      investigationId: 'conf-inv-1',
      finalStatus: 'CONFIRMED',
    };
    const confirmedInv: InvestigationResponse = {
      ...probableInvestigation,
      investigationId: 'conf-inv-1',
    };

    const items = buildAuthoritativeInvestigations([confirmedInv], [confirmedReport]);
    const metrics = calculateVerdictMetrics(items);

    expect(metrics.confirmedCount).toBe(1);
    expect(metrics.probableCount).toBe(0);
    expect(metrics.insufficientCount).toBe(0);
  });

  it('deduplicates investigations so that no run is counted twice', () => {
    // Both raw lists contain the same investigationId
    const items = buildAuthoritativeInvestigations(
      [probableInvestigation],
      [probableAgentReport]
    );

    expect(items).toHaveLength(1);
    expect(items[0].investigationId).toBe('9ffbdd7d-9992-4409-a869-d4792ca3002a');
    expect(items[0].verdict).toBe('PROBABLE');

    const metrics = calculateVerdictMetrics(items);
    expect(metrics.totalAnalyzed).toBe(1);
    expect(metrics.probableCount).toBe(1);
  });

  it('explicitly handles missing reports (deterministic COMPLETED) as pending reports', () => {
    const pendingInv: InvestigationResponse = {
      ...probableInvestigation,
      investigationId: 'pending-inv-1',
      status: 'COMPLETED',
    };

    // No agent report provided
    const items = buildAuthoritativeInvestigations([pendingInv], []);
    expect(items[0].verdict).toBe('REPORT_PENDING');

    const metrics = calculateVerdictMetrics(items);
    expect(metrics.confirmedCount).toBe(0);
    expect(metrics.probableCount).toBe(0);
    expect(metrics.insufficientCount).toBe(0);
    expect(metrics.pendingReportCount).toBe(1);
    expect(metrics.failedCount).toBe(0);
  });

  it('explicitly handles failed investigations', () => {
    const failedInv: InvestigationResponse = {
      ...probableInvestigation,
      investigationId: 'failed-inv-1',
      status: 'FAILED',
    };

    const items = buildAuthoritativeInvestigations([failedInv], []);
    expect(items[0].verdict).toBe('FAILED');

    const metrics = calculateVerdictMetrics(items);
    expect(metrics.confirmedCount).toBe(0);
    expect(metrics.probableCount).toBe(0);
    expect(metrics.insufficientCount).toBe(0);
    expect(metrics.failedCount).toBe(1);
  });

  it('handles empty inputs safely without throwing', () => {
    const items = buildAuthoritativeInvestigations([], []);
    expect(items).toHaveLength(0);

    const metrics = calculateVerdictMetrics(items);
    expect(metrics.totalAnalyzed).toBe(0);
    expect(metrics.confirmedCount).toBe(0);
    expect(metrics.probableCount).toBe(0);
    expect(metrics.insufficientCount).toBe(0);
  });

  it('renders StatsOverview with correct verdict counts and friendly memory wording', () => {
    const rawInvs = [probableInvestigation, insufficientInvestigation];
    const rawReports = [probableAgentReport];

    render(<StatsOverview investigations={rawInvs} agentReports={rawReports} />);

    // Value in card 4
    expect(screen.getByText('0 Confirmed')).toBeInTheDocument();
    // Subtitle in card 4
    expect(screen.getByText(/1 Probable \| 1 Insufficient/i)).toBeInTheDocument();
    // Friendly runtime memory wording in card 1
    expect(screen.getByText(/2 live session records \(in-memory runtime\)/i)).toBeInTheDocument();
    // Total analyzed and Errors Diagnosed both show '2'
    expect(screen.getAllByText('2').length).toBeGreaterThanOrEqual(2);
  });

  it('renders RecentInvestigations with authoritative verdicts and heuristic score labels', () => {
    const rawInvs = [probableInvestigation, insufficientInvestigation];
    const rawReports = [probableAgentReport];

    render(
      <BrowserRouter>
        <RecentInvestigations investigations={rawInvs} agentReports={rawReports} />
      </BrowserRouter>
    );

    // Table headers
    expect(screen.getByText(/Heuristic Score/i)).toBeInTheDocument();
    expect(screen.getByText(/Verdict \/ Status/i)).toBeInTheDocument();

    // Table rows display authoritative verdicts
    expect(screen.getByText('PROBABLE')).toBeInTheDocument();
    expect(screen.getByText('INSUFFICIENT EVIDENCE')).toBeInTheDocument();

    // Scores
    expect(screen.getByText('71.0%')).toBeInTheDocument();

    // Friendly subtitle
    expect(
      screen.getByText(/Live records available in current server session \(in-memory runtime\)/i)
    ).toBeInTheDocument();
  });
});
