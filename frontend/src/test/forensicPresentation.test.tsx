import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BrowserRouter, MemoryRouter, Routes, Route } from 'react-router-dom';
import { HypothesesList } from '../components/investigation/HypothesesList';
import { EvidenceExplorer } from '../components/evidence/EvidenceExplorer';
import { ChronologicalTimeline } from '../components/timeline/ChronologicalTimeline';
import { HistoryPage } from '../pages/HistoryPage';
import { InvestigationDetailPage } from '../pages/InvestigationDetailPage';
import { investigationApi } from '../api/investigationApi';
import type {
  EvidenceItem,
  HypothesisResponse,
  InvestigationAgentReport,
  InvestigationResponse,
  TimelineEvent,
} from '../api/types';

describe('Phase 9.3 Forensic Presentation & Dossier Correctness', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  // Scenario 1, 2, 3: Detail Page Rendering Confirmed Report, Root Cause, Evidence Counts
  describe('Investigation Detail Page (InvestigationDetailPage.tsx)', () => {
    const mockInv: InvestigationResponse = {
      investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
      investigationMode: 'WORKING_TREE_FORENSICS',
      serviceName: 'TacitIQ-EconomicTimes',
      environment: 'Local Development',
      status: 'COMPLETED',
      commitsAnalyzed: 15,
      errorsDetected: 0,
      hypothesesCount: 0,
      claimsCount: 17,
      evidenceSufficient: true,
      topHypothesis: null,
      startedAt: '2026-11-07T22:02:26Z',
      completedAt: '2026-11-07T22:02:27Z',
    };

    const mockReport: InvestigationAgentReport = {
      reportId: 'REP-e2803caa',
      investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
      summary:
        'Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established.',
      primarySuspectedRootCause:
        'Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.',
      alternativeHypotheses: [],
      supportingEvidenceIds: Array.from({ length: 79 }, (_, i) => `EVD-WT-MISSING-${i}`),
      contradictoryEvidenceIds: [],
      aiReasoningSummary:
        'Forensic Analysis:\n- Physical absence is confirmed by filesystem inspection.\n- HEAD commit tracks all missing files.',
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [
        'Restore missing working-tree files from HEAD using git checkout HEAD -- <paths>',
      ],
      remainingUncertainties: [
        'Responsible process (antivirus, background sync, script) not established without OS audit logs',
      ],
      finalStatus: 'CONFIRMED',
      validatedClaims: [],
      investigationSteps: [],
      generatedAt: '2026-11-07T22:02:27Z',
    };

    it('renders confirmed agent report without requiring synthetic topHypothesis', async () => {
      vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(mockReport);
      vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(mockInv);
      vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

      render(
        <MemoryRouter initialEntries={['/investigations/5c48eb24-a00e-435a-aff8-88879ea113fb']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      // Verify verdict
      await waitFor(() => {
        expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
      });

      // Verify root cause
      expect(
        screen.getByText(
          'Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.'
        )
      ).toBeInTheDocument();

      // Verify supporting (79) and contradictory (0) evidence counts
      expect(screen.getByText('79 items')).toBeInTheDocument();
      expect(screen.getByText('0 items')).toBeInTheDocument();

      // Verify agent reasoning, verification steps, and uncertainties
      expect(screen.getByText(/Physical absence is confirmed by filesystem inspection/i)).toBeInTheDocument();
      expect(screen.getByText(/Restore missing working-tree files from HEAD/i)).toBeInTheDocument();
      expect(screen.getByText(/Responsible process .* not established without OS audit logs/i)).toBeInTheDocument();

      // Verify score is Unscored when breakdown is missing (no fabricated 0.95 fallback)
      expect(screen.getAllByText(/Unscored/i).length).toBeGreaterThanOrEqual(1);
      expect(screen.queryByText(/95%/)).not.toBeInTheDocument();
    });
  });

  // Scenario 4: Empty hypotheses explanation banner
  describe('Hypotheses Section (HypothesesList.tsx)', () => {
    it('displays clear explanation when hypotheses array is empty (no synthetic fabrication)', () => {
      render(<HypothesesList hypotheses={[]} />);

      expect(
        screen.getByText(
          "No persisted hypothesis records are available for this investigation. See the agent's confirmed forensic conclusion and supporting evidence below."
        )
      ).toBeInTheDocument();
      // Must not fabricate hypotheses
      expect(screen.queryByText(/Candidate Hypothesis/i)).not.toBeInTheDocument();
    });

    it('displays candidate hypotheses normally when persisted hypotheses exist', () => {
      const persistedHypo: HypothesisResponse = {
        id: 'hypo-1',
        rank: 1,
        confidenceScore: 0.85,
        status: 'CONFIRMED',
        summary: 'Configuration pool exhausted',
        evidenceIds: ['EVD-1'],
        contradictingEvidenceIds: [],
        matchedRules: ['RULE_POOL'],
        ruleMatchReasons: { RULE_POOL: 'Matched' },
        penaltyReasons: [],
      };

      render(<HypothesesList hypotheses={[persistedHypo]} />);

      expect(screen.getByText('Configuration pool exhausted')).toBeInTheDocument();
      expect(screen.getByText('85.0%')).toBeInTheDocument();
      expect(
        screen.queryByText(/No persisted hypothesis records are available/i)
      ).not.toBeInTheDocument();
    });
  });

  // Scenario 5: Working-tree evidence filter and dynamic discovery
  describe('Evidence Explorer (EvidenceExplorer.tsx)', () => {
    const mockEvidence: EvidenceItem[] = [
      ...Array.from({ length: 79 }, (_, i) => ({
        evidenceId: `EVD-WT-MISSING-${i}`,
        evidenceType: 'WORKING_TREE_CHANGE' as const,
        source: 'WORKING_TREE',
        originalReference: `file_${i}.java`,
        summary: `Tracked file missing from disk: src/file_${i}.java`,
        metadata: { changeType: 'MISSING', path: `src/file_${i}.java` },
      })),
      {
        evidenceId: 'EVD-WT-MOD-1',
        evidenceType: 'WORKING_TREE_CHANGE' as const,
        source: 'WORKING_TREE',
        originalReference: 'README.md',
        summary: 'Modified working-tree file: README.md',
        metadata: { changeType: 'MODIFIED', path: 'README.md' },
      },
      {
        evidenceId: 'EVD-COMMIT-1',
        evidenceType: 'COMMIT' as const,
        source: 'GIT',
        originalReference: 'd711d65',
        summary: 'HEAD commit d711d65',
        metadata: { sha: 'd711d65' },
      },
    ];

    it('dynamically generates filter tabs including Working Tree Changes and Commits', () => {
      render(<EvidenceExplorer evidenceItems={mockEvidence} />);

      // Filter tabs derived dynamically from present evidence
      expect(screen.getByText('All Items')).toBeInTheDocument();
      expect(screen.getByText('Working Tree Changes')).toBeInTheDocument();
      expect(screen.getByText('Commits')).toBeInTheDocument();

      // Counts inside tabs: 80 working tree (79 missing + 1 modified), 1 commit, 81 total
      expect(screen.getByText('81')).toBeInTheDocument(); // All Items count
      expect(screen.getByText('80')).toBeInTheDocument(); // Working Tree Changes count
      expect(screen.getByText('1')).toBeInTheDocument(); // Commits count
    });

    it('filters by Working Tree Changes tab and keeps all 79 missing items visible', () => {
      render(<EvidenceExplorer evidenceItems={mockEvidence} />);

      const wtTab = screen.getByText('Working Tree Changes');
      fireEvent.click(wtTab);

      // Verify filtered summary
      expect(
        screen.getByText(/Showing 80 of 81 evidence items \(filtered by Working Tree Changes\)/i)
      ).toBeInTheDocument();

      // Search within working tree changes
      const searchInput = screen.getByPlaceholderText(/Search evidence by ID/i);
      fireEvent.change(searchInput, { target: { value: 'file_0.java' } });

      expect(
        screen.getByText('Tracked file missing from disk: src/file_0.java')
      ).toBeInTheDocument();
    });
  });

  // Scenario 6: Working-tree anomaly timeline styling
  describe('Timeline (ChronologicalTimeline.tsx)', () => {
    const mockEvents: TimelineEvent[] = [
      {
        eventId: 'evt-wt-1',
        timestamp: '2026-11-07T16:13:19.283409Z',
        eventType: 'WORKING_TREE_ANOMALY',
        source: 'WORKING_TREE',
        entityId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
        title: 'Working tree anomaly: 79 tracked file(s) missing from disk',
        description:
          'Working tree anomaly: 79 tracked file(s) missing from disk (present in HEAD d711d65), 1 modified file(s)',
        isPreFailure: false,
        evidenceId: 'EVD-WT-MISSING-2d6e2bee8e',
        metadata: {
          missingCount: 79,
          headCommitSha: 'd711d65a3e2fecd40257af992993bfab07fb7a24',
          modifiedCount: 1,
        },
      },
      {
        eventId: 'evt-commit-1',
        timestamp: '2026-11-07T15:00:00Z',
        eventType: 'COMMIT',
        source: 'GIT',
        entityId: 'd711d65',
        title: 'Commit: update README',
        description: 'Update repository documentation',
        isPreFailure: true,
        metadata: {},
      },
    ];

    it('renders distinct visual treatment for WORKING_TREE_ANOMALY with badge and counts', () => {
      render(<ChronologicalTimeline events={mockEvents} />);

      // Title & badge
      expect(
        screen.getByText('Working tree anomaly: 79 tracked file(s) missing from disk')
      ).toBeInTheDocument();
      expect(screen.getByText('Filesystem Anomaly')).toBeInTheDocument();
      expect(screen.getByText('WORKING_TREE_ANOMALY')).toBeInTheDocument();

      // Specific missingCount breakdown
      expect(screen.getByText('Missing Tracked Files: 79')).toBeInTheDocument();
      expect(screen.getByText('| Modified: 1')).toBeInTheDocument();

      // Distinguishable from Commit
      expect(screen.getByText('Commit: update README')).toBeInTheDocument();
      expect(screen.getByText('Pre-Failure')).toBeInTheDocument();
    });
  });

  // Scenario 7 & 8: History Page verdict aggregation and un-reported investigations
  describe('History Page (HistoryPage.tsx)', () => {
    it('aggregates persisted agent reports with CONFIRMED status and preserves un-reported investigations', async () => {
      const mockInvs: InvestigationResponse[] = [
        {
          investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
          investigationMode: 'WORKING_TREE_FORENSICS',
          serviceName: 'TacitIQ-EconomicTimes',
          environment: 'Local Development',
          status: 'COMPLETED',
          commitsAnalyzed: 15,
          errorsDetected: 0,
          hypothesesCount: 0,
          claimsCount: 17,
          evidenceSufficient: true,
          topHypothesis: null,
          startedAt: '2026-11-07T22:02:26Z',
          completedAt: '2026-11-07T22:02:27Z',
        },
        {
          investigationId: 'unreported-inv-1234',
          investigationMode: 'RUNTIME_FAILURE',
          serviceName: 'order-service',
          environment: 'production',
          status: 'COMPLETED',
          commitsAnalyzed: 5,
          errorsDetected: 1,
          hypothesesCount: 2,
          claimsCount: 3,
          evidenceSufficient: true,
          topHypothesis: {
            id: 'hypo-2',
            rank: 1,
            confidenceScore: 0.85,
            status: 'PROBABLE',
            summary: 'HikariCP connection leak',
            evidenceIds: ['EVD-2'],
            contradictingEvidenceIds: [],
            matchedRules: [],
            ruleMatchReasons: {},
            penaltyReasons: [],
          },
          startedAt: '2026-11-07T20:00:00Z',
          completedAt: '2026-11-07T20:01:00Z',
        },
      ];

      const mockReports: InvestigationAgentReport[] = [
        {
          reportId: 'REP-e2803caa',
          investigationId: '5c48eb24-a00e-435a-aff8-88879ea113fb',
          summary: 'Working tree anomaly: 79 tracked files missing from disk',
          primarySuspectedRootCause:
            'Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.',
          alternativeHypotheses: [],
          supportingEvidenceIds: Array.from({ length: 79 }, (_, i) => `EVD-WT-${i}`),
          contradictoryEvidenceIds: [],
          aiReasoningSummary: 'Forensic analysis',
          externalResearchFindings: [],
          sourceUrls: [],
          recommendedVerificationSteps: ['Restore missing files'],
          remainingUncertainties: ['Deletion process unknown'],
          finalStatus: 'CONFIRMED',
          validatedClaims: [],
          investigationSteps: [],
          generatedAt: '2026-11-07T22:02:27Z',
        },
      ];

      vi.spyOn(investigationApi, 'getAllInvestigations').mockResolvedValue(mockInvs);
      vi.spyOn(investigationApi, 'getAllAgentReports').mockResolvedValue(mockReports);

      render(
        <BrowserRouter>
          <HistoryPage />
        </BrowserRouter>
      );

      // Verify header updated copy (no in-memory wording)
      expect(
        screen.getByText('Complete persistent record of autonomous forensic investigations.')
      ).toBeInTheDocument();

      // Wait for table to load
      await waitFor(() => {
        expect(screen.getByText('TacitIQ-EconomicTimes')).toBeInTheDocument();
      });

      // CONFIRMED status shown for TacitIQ (merged from agent report instead of raw COMPLETED)
      const confirmedElements = screen.getAllByText('CONFIRMED');
      expect(confirmedElements.length).toBeGreaterThanOrEqual(1);

      // Root cause displayed without topHypothesis
      expect(
        screen.getByText(/Working tree filesystem anomaly: Tracked files are missing from disk/i)
      ).toBeInTheDocument();
      // Candidate score displays Unscored for confirmed working tree investigation with missing breakdown (no fabricated 95.0% Grounded)
      expect(screen.getByText('Unscored')).toBeInTheDocument();
      expect(screen.queryByText(/95(\.0)?%/)).not.toBeInTheDocument();
      expect(screen.queryByText(/Grounded/i)).not.toBeInTheDocument();

      // Investigation without agent report is preserved and displayed as REPORT PENDING
      expect(screen.getByText('order-service')).toBeInTheDocument();
      expect(screen.getByText('REPORT PENDING')).toBeInTheDocument();
      expect(screen.getByText('HikariCP connection leak')).toBeInTheDocument();
      expect(screen.getByText('85.0%')).toBeInTheDocument();
    });
  });

  // Scenario 9: Loading and API error states
  describe('Investigation Detail Page Loading & Error States', () => {
    it('displays error state with retry button when investigation is not found', async () => {
      vi.spyOn(investigationApi, 'getAgentReport').mockRejectedValue(new Error('HTTP 404: Not Found'));
      vi.spyOn(investigationApi, 'getInvestigation').mockRejectedValue(new Error('HTTP 404: Not Found'));

      render(
        <MemoryRouter initialEntries={['/investigations/unknown-id-123']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText('Investigation Dossier Not Found')).toBeInTheDocument();
      });

      // Verified updated copy without "runtime memory"
      expect(screen.getByText(/found in database records/i)).toBeInTheDocument();
      expect(screen.getByText('Retry Request')).toBeInTheDocument();
      expect(screen.getByText('Return to History')).toBeInTheDocument();
    });
  });
});
