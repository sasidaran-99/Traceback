import React, { useState } from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EvidenceExplorer } from '../components/evidence/EvidenceExplorer';
import { SettingsPage } from '../pages/SettingsPage';
import { InvestigationDetailPage } from '../pages/InvestigationDetailPage';
import { investigationApi } from '../api/investigationApi';
import type {
  EvidenceItem,
  InvestigationResponse,
  InvestigationAgentReport,
} from '../api/types';

describe('Phase 2.33 — Targeted UX Improvements', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('F-01: Investigation Tab Navigation', () => {
    const mockInv: InvestigationResponse = {
      investigationId: 'inv-test-tab-1',
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
      reportId: 'REP-tab-test',
      investigationId: 'inv-test-tab-1',
      summary: 'Test investigation summary narrative',
      primarySuspectedRootCause: 'Primary suspected root cause',
      alternativeHypotheses: [],
      supportingEvidenceIds: ['EVD-1'],
      contradictoryEvidenceIds: [],
      aiReasoningSummary: 'Test AI reasoning summary',
      externalResearchFindings: [],
      sourceUrls: [],
      recommendedVerificationSteps: [],
      remainingUncertainties: [],
      finalStatus: 'CONFIRMED',
    };

    it('renders all 7 dossier tabs inside the tablist with proper accessibility semantics', async () => {
      vi.spyOn(investigationApi, 'getInvestigation').mockResolvedValue(mockInv);
      vi.spyOn(investigationApi, 'getAgentReport').mockResolvedValue(mockReport);
      vi.spyOn(investigationApi, 'getHypotheses').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getClaims').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getEvidence').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getTimeline').mockResolvedValue([]);
      vi.spyOn(investigationApi, 'getAgentSteps').mockResolvedValue([]);

      render(
        <MemoryRouter initialEntries={['/investigations/inv-test-tab-1']}>
          <Routes>
            <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByRole('tablist', { name: /Investigation Dossier Tabs/i })).toBeDefined();
      });

      const tabs = screen.getAllByRole('tab');
      expect(tabs.length).toBe(8);

      // Verify individual tab labels
      const tabLabels = tabs.map((t) => t.textContent);
      expect(tabLabels.some((l) => l?.includes('AI Report & Overview'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Hypotheses'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Claims'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Evidence'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Timeline'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Research'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Agent Steps'))).toBe(true);
      expect(tabLabels.some((l) => l?.includes('Remediations'))).toBe(true);

      // Default active tab should be Overview
      const overviewTab = tabs.find((t) => t.textContent?.includes('AI Report & Overview'));
      expect(overviewTab?.getAttribute('aria-selected')).toBe('true');
      expect(overviewTab?.getAttribute('data-active')).toBe('true');

      // Click Claims tab
      const claimsTab = tabs.find((t) => t.textContent?.includes('Claims'));
      expect(claimsTab).toBeDefined();
      fireEvent.click(claimsTab!);

      expect(claimsTab?.getAttribute('aria-selected')).toBe('true');
      expect(claimsTab?.getAttribute('data-active')).toBe('true');
      expect(overviewTab?.getAttribute('aria-selected')).toBe('false');
    });
  });

  describe('F-02: Settings Page Header & Breadcrumbs', () => {
    it('renders breadcrumb hierarchy (Platform / Settings) linking to root', () => {
      render(
        <MemoryRouter initialEntries={['/settings']}>
          <SettingsPage />
        </MemoryRouter>
      );

      const breadcrumbNav = screen.getByRole('navigation', { name: /breadcrumb/i });
      expect(breadcrumbNav).toBeDefined();

      const platformLink = screen.getByRole('link', { name: /Platform/i });
      expect(platformLink).toBeDefined();
      expect(platformLink.getAttribute('href')).toBe('/');

      expect(screen.getByText('Settings')).toBeDefined();
    });

    it('renders the approved page heading and explanatory subtitle', () => {
      render(
        <MemoryRouter initialEntries={['/settings']}>
          <SettingsPage />
        </MemoryRouter>
      );

      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading.textContent).toContain('System Settings & Configuration');

      const subtitle = screen.getByText(
        'Review core engine runtime status, external AI research gateways, deterministic scoring parameters, and authorized repository paths.'
      );
      expect(subtitle).toBeDefined();
    });
  });

  describe('F-03: Evidence Explorer Search Reset', () => {
    const mockEvidence: EvidenceItem[] = [
      {
        evidenceId: 'ev-wt-001',
        investigationId: 'inv-1',
        evidenceType: 'WORKING_TREE_CHANGE',
        source: 'LOCAL_GIT',
        summary: 'Deleted src/main/java/com/example/Service.java',
        integrityHash: 'a1b2c3d4e5f67890',
        timestamp: '2026-10-10T12:00:00Z',
      },
      {
        evidenceId: 'ev-commit-002',
        investigationId: 'inv-1',
        evidenceType: 'COMMIT',
        source: 'LOCAL_GIT',
        summary: 'Commit 8f1bbf6: Reduce thread pool size',
        integrityHash: 'b2c3d4e5f67890a1',
        timestamp: '2026-10-10T12:05:00Z',
      },
      {
        evidenceId: 'ev-log-003',
        investigationId: 'inv-1',
        evidenceType: 'LOG_ERROR',
        source: 'LOG_FILE',
        summary: 'Connection pool exhausted timeout',
        integrityHash: 'c3d4e5f67890a1b2',
        timestamp: '2026-10-10T12:10:00Z',
      },
    ];

    const Harness: React.FC<{ initialId?: string; onClear?: () => void }> = ({
      initialId,
      onClear,
    }) => {
      const [highlightedId, setHighlightedId] = useState<string | undefined>(initialId);
      return (
        <EvidenceExplorer
          evidenceItems={mockEvidence}
          highlightedEvidenceId={highlightedId}
          onClearHighlight={() => {
            setHighlightedId(undefined);
            onClear?.();
          }}
        />
      );
    };

    it('renders "Clear selection" control beside the SELECTED FROM CLAIM badge when an item is highlighted', () => {
      const handleClear = vi.fn();
      render(<Harness initialId="ev-commit-002" onClear={handleClear} />);

      // Verify the badge and the clear button exist
      expect(screen.getByText('SELECTED FROM CLAIM')).toBeDefined();
      const clearBtn = screen.getByRole('button', {
        name: /Clear selection and restore all evidence items/i,
      });
      expect(clearBtn).toBeDefined();

      // Only the highlighted item is visible before clearing
      expect(
        screen.queryByText(/Deleted src\/main\/java\/com\/example\/Service\.java/i)
      ).toBeNull();
      expect(
        screen.getByText(/Commit 8f1bbf6: Reduce thread pool size/i)
      ).toBeDefined();

      // Click "Clear selection"
      fireEvent.click(clearBtn);

      // Verify onClear callback was invoked
      expect(handleClear).toHaveBeenCalledTimes(1);

      // After clearing, all evidence items are restored to view
      expect(
        screen.getByText(/Deleted src\/main\/java\/com\/example\/Service\.java/i)
      ).toBeDefined();
      expect(
        screen.getByText(/Connection pool exhausted timeout/i)
      ).toBeDefined();
    });

    it('renders "Reset filter" control in the top filter banner and resets on click', () => {
      const handleClear = vi.fn();
      render(<Harness initialId="ev-wt-001" onClear={handleClear} />);

      const resetBannerBtn = screen.getByRole('button', {
        name: /Reset filter and restore all evidence items/i,
      });
      expect(resetBannerBtn).toBeDefined();

      fireEvent.click(resetBannerBtn);
      expect(handleClear).toHaveBeenCalledTimes(1);

      // Full list restored
      expect(
        screen.getByText(/Commit 8f1bbf6: Reduce thread pool size/i)
      ).toBeDefined();
      expect(
        screen.getByText(/Connection pool exhausted timeout/i)
      ).toBeDefined();
    });
  });
});
