import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HypothesesList } from '../components/investigation/HypothesesList';
import { EvidenceExplorer } from '../components/evidence/EvidenceExplorer';
import { InvestigationDetailPage } from '../pages/InvestigationDetailPage';
import { investigationApi } from '../api/investigationApi';
import type {
  EvidenceItem,
  HypothesisResponse,
  InvestigationAgentReport,
  InvestigationResponse,
} from '../api/types';

describe('Phase 2.54.2 — Core Forensic Workbench Ergonomics (P0 Remediation)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  const mockHypotheses: HypothesisResponse[] = [
    {
      id: 'hypo-1',
      rank: 1,
      confidenceScore: 0.85,
      status: 'CONFIRMED',
      summary: 'Connection pool configuration exhausted',
      commitSha: 'd711d65a3e2f',
      matchedRules: ['RULE_POOL'],
      supportingEvidenceIds: ['EVD-CONF-001', 'EVD-STACK-002'],
      contradictingEvidenceIds: [],
      counterEvidence: null,
      description: 'Maximum pool size was configured below concurrent worker threshold.',
      hasStructuralOverlap: true,
      hasConfigOverlap: true,
    },
    {
      id: 'hypo-2',
      rank: 2,
      confidenceScore: 0.45,
      status: 'PROBABLE',
      summary: 'Network socket reset',
      commitSha: 'a1b2c3d4e5f6',
      matchedRules: ['RULE_NET'],
      supportingEvidenceIds: ['EVD-NET-003'],
      contradictingEvidenceIds: [],
      counterEvidence: 'Socket keepalive was active during incident',
      description: 'Transient TCP RST seen in external client logs.',
      hasStructuralOverlap: false,
      hasConfigOverlap: false,
    },
  ];

  const mockEvidenceItems: EvidenceItem[] = [
    {
      evidenceId: 'EVD-CONF-001',
      evidenceType: 'CONFIG_DIFF',
      source: 'LOCAL_GIT',
      summary: 'Database pool reduced from 20 to 2',
      originalReference: 'diff --git a/application.yml b/application.yml\n@@ -10,3 +10,3 @@\n-  maximum-pool-size: 20\n+  maximum-pool-size: 2',
      integrityHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      timestamp: '2026-11-18T10:00:00Z',
      metadata: { path: 'application.yml', changedKey: 'maximum-pool-size' },
    },
    {
      evidenceId: 'EVD-STACK-002',
      evidenceType: 'STACK_TRACE',
      source: 'APPLICATION_LOG',
      summary: 'ConnectionTimeoutException in OrderService',
      originalReference: 'java.sql.SQLTimeoutException: Connection timeout after 30000ms\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\n\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)',
      integrityHash: 'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb',
      timestamp: '2026-11-18T10:05:00Z',
      metadata: { thread: 'http-nio-8080-exec-1' },
    },
  ];

  describe('1 & 2. UX-054-05: Hypothesis Evidence Navigation & Keyboard Support', () => {
    it('renders evidence IDs as interactive buttons and calls onSelectEvidence on click', () => {
      const handleSelectEvidence = vi.fn();
      render(
        <HypothesesList
          hypotheses={mockHypotheses}
          onSelectEvidence={handleSelectEvidence}
        />
      );

      // Hypothesis 1 is expanded by default (rank 1)
      const evBtn1 = screen.getByRole('button', { name: /Inspect evidence item EVD-CONF-001/i });
      const evBtn2 = screen.getByRole('button', { name: /Inspect evidence item EVD-STACK-002/i });

      expect(evBtn1).toBeInTheDocument();
      expect(evBtn2).toBeInTheDocument();

      fireEvent.click(evBtn1);
      expect(handleSelectEvidence).toHaveBeenCalledTimes(1);
      expect(handleSelectEvidence).toHaveBeenCalledWith('EVD-CONF-001');

      fireEvent.click(evBtn2);
      expect(handleSelectEvidence).toHaveBeenCalledTimes(2);
      expect(handleSelectEvidence).toHaveBeenCalledWith('EVD-STACK-002');
    });

    it('activates evidence buttons using native Enter and Space keyboard events', () => {
      const handleSelectEvidence = vi.fn();
      render(
        <HypothesesList
          hypotheses={mockHypotheses}
          onSelectEvidence={handleSelectEvidence}
        />
      );

      const evBtn1 = screen.getByRole('button', { name: /Inspect evidence item EVD-CONF-001/i });

      // Native buttons dispatch click on Enter and Space in browsers/JSDOM
      fireEvent.click(evBtn1);
      expect(handleSelectEvidence).toHaveBeenCalledWith('EVD-CONF-001');

      // Click propagation must not collapse parent accordion
      expect(screen.getByText(/Supporting Evidence \(2\)/i)).toBeInTheDocument();
    });
  });

  describe('3. UX-054-21: Semantic Accordion Keyboard Accessibility', () => {
    it('uses semantic button triggers with aria-expanded and aria-controls for hypotheses', () => {
      render(<HypothesesList hypotheses={mockHypotheses} />);

      // HypothesesList trigger buttons
      const headerTrigger1 = document.getElementById('hypothesis-header-hypo-1');
      expect(headerTrigger1).toBeInTheDocument();
      expect(headerTrigger1?.tagName).toBe('BUTTON');
      expect(headerTrigger1?.getAttribute('aria-expanded')).toBe('true');
      expect(headerTrigger1?.getAttribute('aria-controls')).toBe('hypothesis-details-hypo-1');

      // Controlled region
      const region1 = document.getElementById('hypothesis-details-hypo-1');
      expect(region1).toBeInTheDocument();
      expect(region1?.getAttribute('role')).toBe('region');
      expect(region1?.getAttribute('aria-labelledby')).toBe('hypothesis-header-hypo-1');

      // Collapse with click / Enter key
      fireEvent.click(headerTrigger1!);
      expect(headerTrigger1?.getAttribute('aria-expanded')).toBe('false');
      expect(document.getElementById('hypothesis-details-hypo-1')).toBeNull();

      // Expand again
      fireEvent.click(headerTrigger1!);
      expect(headerTrigger1?.getAttribute('aria-expanded')).toBe('true');
      expect(document.getElementById('hypothesis-details-hypo-1')).toBeInTheDocument();
    });

    it('uses semantic button triggers with aria-expanded and aria-controls for EvidenceExplorer', () => {
      render(<EvidenceExplorer evidenceItems={mockEvidenceItems} />);

      const triggerBtn = screen.getByRole('button', {
        name: /Expand details for EVD-CONF-001/i,
      });
      expect(triggerBtn).toBeInTheDocument();
      expect(triggerBtn.getAttribute('aria-expanded')).toBe('false');
      expect(triggerBtn.getAttribute('aria-controls')).toBe('evidence-details-EVD-CONF-001');

      // Expand details
      fireEvent.click(triggerBtn);
      expect(triggerBtn.getAttribute('aria-expanded')).toBe('true');

      const detailsRegion = document.getElementById('evidence-details-EVD-CONF-001');
      expect(detailsRegion).toBeInTheDocument();
      expect(detailsRegion?.getAttribute('role')).toBe('region');
      expect(detailsRegion?.getAttribute('aria-labelledby')).toBe('evidence-trigger-EVD-CONF-001');

      // Collapse details
      fireEvent.click(triggerBtn);
      expect(triggerBtn.getAttribute('aria-expanded')).toBe('false');
      expect(document.getElementById('evidence-details-EVD-CONF-001')).toBeNull();
    });
  });

  describe('4. UX-054-07: Bounded Evidence Payloads & Copy Action', () => {
    it('renders expanded evidence payloads inside bounded max-h-96 container with copy action', async () => {
      const writeTextMock = vi.fn().mockResolvedValue(undefined);
      Object.assign(navigator, {
        clipboard: { writeText: writeTextMock },
      });

      render(<EvidenceExplorer evidenceItems={mockEvidenceItems} />);

      // Expand card 1
      const triggerBtn = screen.getByRole('button', {
        name: /Expand details for EVD-CONF-001/i,
      });
      fireEvent.click(triggerBtn);

      // Verify bounded container with max-h-96 and pre formatting
      const detailsRegion = document.getElementById('evidence-details-EVD-CONF-001');
      expect(detailsRegion).toBeInTheDocument();

      const preBlock = detailsRegion?.querySelector('pre');
      expect(preBlock).toBeInTheDocument();
      expect(preBlock?.textContent).toContain('maximum-pool-size: 2');
      expect(preBlock?.className).toContain('font-mono');
      expect(preBlock?.className).toContain('whitespace-pre');

      const scrollContainer = preBlock?.parentElement;
      expect(scrollContainer?.className).toContain('max-h-96');
      expect(scrollContainer?.className).toContain('overflow-y-auto');

      // Copy payload button
      const copyPayloadBtn = screen.getByRole('button', {
        name: /Copy raw evidence payload for EVD-CONF-001/i,
      });
      expect(copyPayloadBtn).toBeInTheDocument();

      fireEvent.click(copyPayloadBtn);
      expect(writeTextMock).toHaveBeenCalledWith(mockEvidenceItems[0].originalReference);
    });
  });

  describe('5. UX-054-03: Verdict-Aware Root-Cause Styling', () => {
    const buildMockInv = (status: string): InvestigationResponse => ({
      investigationId: `inv-${status.toLowerCase()}`,
      serviceName: 'order-service',
      environment: 'production',
      status,
      commitsAnalyzed: 10,
      errorsDetected: 1,
      hypothesesCount: 1,
      claimsCount: 2,
      evidenceSufficient: true,
      topHypothesis: {
        id: 'hypo-1',
        rank: 1,
        confidenceScore: 0.9,
        status,
        summary: 'Database connection leak observed',
        evidenceIds: ['EVD-1'],
        contradictingEvidenceIds: [],
        matchedRules: [],
        ruleMatchReasons: {},
        penaltyReasons: [],
      },
      startedAt: '2026-11-18T10:00:00Z',
      completedAt: '2026-11-18T10:01:00Z',
    });

    const buildMockReport = (verdict: 'CONFIRMED' | 'PROBABLE' | 'INSUFFICIENT_EVIDENCE'): InvestigationAgentReport => ({
      reportId: `REP-${verdict}`,
      investigationId: `inv-${verdict.toLowerCase()}`,
      summary: `Test summary for ${verdict}`,
      primarySuspectedRootCause: `Primary root cause for ${verdict}`,
      alternativeHypotheses: [],
      supportingEvidenceIds: ['EVD-1'],
      contradictoryEvidenceIds: [],
      aiReasoningSummary: `Reasoning for ${verdict}`,
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [],
      remainingUncertainties: [],
      finalStatus: verdict,
      generatedAt: '2026-11-18T10:01:00Z',
    });

    it('renders green semantic border (border-l-[#4FA88A]) for CONFIRMED verdict', async () => {
      vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(buildMockInv('CONFIRMED'));
      vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(buildMockReport('CONFIRMED'));
      vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

      render(
        <MemoryRouter initialEntries={['/investigations/inv-confirmed']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText('Primary root cause for CONFIRMED')).toBeInTheDocument();
      });

      const rootCauseBox = screen.getByText('Primary root cause for CONFIRMED').closest('div');
      expect(rootCauseBox?.className).toContain('border-l-[#4FA88A]');
    });

    it('renders amber semantic border (border-l-[#C9A24A]) for PROBABLE verdict', async () => {
      vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(buildMockInv('PROBABLE'));
      vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(buildMockReport('PROBABLE'));
      vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

      render(
        <MemoryRouter initialEntries={['/investigations/inv-probable']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText('Primary root cause for PROBABLE')).toBeInTheDocument();
      });

      const rootCauseBox = screen.getByText('Primary root cause for PROBABLE').closest('div');
      expect(rootCauseBox?.className).toContain('border-l-[#C9A24A]');
    });

    it('renders neutral slate border (border-l-[#7C8796]) for INSUFFICIENT_EVIDENCE verdict', async () => {
      vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(buildMockInv('INSUFFICIENT_EVIDENCE'));
      vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(buildMockReport('INSUFFICIENT_EVIDENCE'));
      vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

      render(
        <MemoryRouter initialEntries={['/investigations/inv-insufficient_evidence']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText('Primary root cause for INSUFFICIENT_EVIDENCE')).toBeInTheDocument();
      });

      const rootCauseBox = screen.getByText('Primary root cause for INSUFFICIENT_EVIDENCE').closest('div');
      expect(rootCauseBox?.className).toContain('border-l-[#7C8796]');
    });
  });

  describe('6. Evidence Navigation Flow & Context Preservation', () => {
    it('navigates from Hypotheses tab to Evidence tab and selects the clicked evidence ID', async () => {
      const mockInv: InvestigationResponse = {
        investigationId: 'inv-nav-test',
        serviceName: 'order-service',
        environment: 'production',
        status: 'CONFIRMED',
        commitsAnalyzed: 5,
        errorsDetected: 1,
        hypothesesCount: 1,
        claimsCount: 1,
        evidenceSufficient: true,
        startedAt: '2026-11-18T10:00:00Z',
        completedAt: '2026-11-18T10:01:00Z',
      };

      const mockReport: InvestigationAgentReport = {
        reportId: 'REP-nav',
        investigationId: 'inv-nav-test',
        summary: 'Investigation summary',
        primarySuspectedRootCause: 'Confirmed root cause',
        alternativeHypotheses: [],
        supportingEvidenceIds: ['EVD-CONF-001'],
        contradictoryEvidenceIds: [],
        aiReasoningSummary: 'Reasoning narrative',
        externalResearchFindings: [],
        sourceUrls: [],
        recommendedVerificationSteps: [],
        remainingUncertainties: [],
        finalStatus: 'CONFIRMED',
        generatedAt: '2026-11-18T10:01:00Z',
      };

      vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(mockInv);
      vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(mockReport);
      vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue(mockHypotheses);
      vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue(mockEvidenceItems);
      vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getResearchFindings').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

      render(
        <MemoryRouter initialEntries={['/investigations/inv-nav-test']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      // Wait for page load
      await waitFor(() => {
        expect(screen.getByRole('tablist')).toBeInTheDocument();
      });

      // Switch to Hypotheses tab
      const hypothesesTab = screen.getByRole('tab', { name: /Hypotheses/i });
      fireEvent.click(hypothesesTab);

      // Click the evidence pill in Hypothesis 1
      const evBtn = await screen.findByRole('button', {
        name: /Inspect evidence item EVD-CONF-001/i,
      });
      fireEvent.click(evBtn);

      // Verify active tab switched to Evidence
      await waitFor(() => {
        const evidenceTab = screen.getByRole('tab', { name: /Evidence/i });
        expect(evidenceTab.getAttribute('aria-selected')).toBe('true');
      });

      // Verify the selected evidence card is highlighted and selected badge is present
      expect(screen.getByText('SELECTED FROM CLAIM')).toBeInTheDocument();
      expect(screen.getAllByText('Database pool reduced from 20 to 2').length).toBeGreaterThanOrEqual(1);
    });
  });
});
