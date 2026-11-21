import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ClaimValidationTable } from '../components/investigation/ClaimValidationTable';
import type { InvestigationClaim } from '../api/types';

describe('ClaimValidationTable', () => {
  it('renders validated claims with status, evidence IDs, and explanation', () => {
    const mockClaims: InvestigationClaim[] = [
      {
        claimId: 'claim-1',
        investigationId: 'inv-1',
        statement: 'Commit 8f1bbf6 reduced maximum pool size from 20 to 2',
        status: 'SUPPORTED',
        supportingEvidenceIds: ['ev-diff-1', 'ev-config-2'],
        contradictingEvidenceIds: [],
        explanation: 'Verified against git diff hunk in application.yml',
        assertedAt: '2026-10-10T14:30:00Z',
      },
      {
        claimId: 'claim-2',
        investigationId: 'inv-1',
        statement: 'Log messages indicate memory leak in garbage collector',
        status: 'UNSUPPORTED',
        supportingEvidenceIds: [],
        contradictingEvidenceIds: ['ev-log-3'],
        explanation: 'No memory leak indicators found in provided logs',
        assertedAt: '2026-10-10T14:31:00Z',
      },
      {
        claimId: 'claim-3',
        investigationId: 'inv-1',
        statement: 'Candidate commit referenced quarantined evidence',
        status: 'VALIDATION_ERROR',
        supportingEvidenceIds: ['ev-quarantine-1'],
        contradictingEvidenceIds: [],
        explanation: 'Validation failed: Supporting evidence ID not found in active registry',
        assertedAt: '2026-10-10T14:32:00Z',
      },
    ];

    render(<ClaimValidationTable claims={mockClaims} />);

    // Grounding banner with distinct status counts
    expect(screen.getByText('Claim Validation Grounding Engine')).toBeInTheDocument();
    expect(screen.getByText('Supported: 1')).toBeInTheDocument();
    expect(screen.getByText('Validation errors: 1')).toBeInTheDocument();
    expect(screen.getByText('Contradicted: 0')).toBeInTheDocument();
    expect(screen.getByText('Unsupported: 1')).toBeInTheDocument();

    // Status filter buttons
    expect(screen.getByRole('button', { name: /^All \(3\)/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Supported \(1\)/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Validation Errors \(1\)/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Contradicted \(0\)/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Unsupported \(1\)/i })).toBeInTheDocument();

    // Claim 1
    expect(
      screen.getByText('"Commit 8f1bbf6 reduced maximum pool size from 20 to 2"')
    ).toBeInTheDocument();
    expect(screen.getByText('ev-diff-1')).toBeInTheDocument();
    expect(screen.getByText('ev-config-2')).toBeInTheDocument();
    expect(screen.getByText('Verified against git diff hunk in application.yml')).toBeInTheDocument();

    // Claim 2
    expect(
      screen.getByText('"Log messages indicate memory leak in garbage collector"')
    ).toBeInTheDocument();
    expect(screen.getByText('Zero evidence IDs cited')).toBeInTheDocument();
    expect(screen.getByText('ev-log-3')).toBeInTheDocument();

    // Claim 3
    expect(
      screen.getByText('"Candidate commit referenced quarantined evidence"')
    ).toBeInTheDocument();
    expect(screen.getByText('Validation failed: Supporting evidence ID not found in active registry')).toBeInTheDocument();
  });

  it('invokes onSelectEvidence when clicking supporting or counter-evidence IDs', () => {
    const onSelect = vi.fn();
    const mockClaims: InvestigationClaim[] = [
      {
        claimId: 'claim-1',
        investigationId: 'inv-1',
        statement: 'Test claim with both supporting and counter evidence',
        status: 'SUPPORTED',
        supportingEvidenceIds: ['EVD-GIT-1', 'EVD-CFG-2'],
        contradictingEvidenceIds: ['EVD-LOG-9'],
        explanation: 'Test explanation',
        assertedAt: '2026-10-10T14:30:00Z',
      },
    ];

    render(<ClaimValidationTable claims={mockClaims} onSelectEvidence={onSelect} />);

    // Click supporting evidence
    const supBtn = screen.getByRole('button', { name: /View supporting evidence EVD-GIT-1/i });
    fireEvent.click(supBtn);
    expect(onSelect).toHaveBeenCalledWith('EVD-GIT-1');

    // Click counter evidence
    const counterBtn = screen.getByRole('button', { name: /View counter-evidence EVD-LOG-9/i });
    fireEvent.click(counterBtn);
    expect(onSelect).toHaveBeenCalledWith('EVD-LOG-9');

    // Keyboard navigation (Enter key)
    fireEvent.keyDown(supBtn, { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledTimes(3);
  });

  it('filters claims by status when status filter button is clicked', () => {
    const mockClaims: InvestigationClaim[] = [
      {
        claimId: 'claim-supported',
        investigationId: 'inv-1',
        statement: 'Supported statement',
        status: 'SUPPORTED',
        supportingEvidenceIds: ['ev-1'],
        contradictingEvidenceIds: [],
        explanation: 'Supported explanation',
        assertedAt: '2026-10-10T14:30:00Z',
      },
      {
        claimId: 'claim-valerr',
        investigationId: 'inv-1',
        statement: 'Validation error statement',
        status: 'VALIDATION_ERROR',
        supportingEvidenceIds: ['ev-2'],
        contradictingEvidenceIds: [],
        explanation: 'Validation error explanation',
        assertedAt: '2026-10-10T14:31:00Z',
      },
    ];

    render(<ClaimValidationTable claims={mockClaims} />);

    // Initially all 2 are rendered
    expect(screen.getByText('"Supported statement"')).toBeInTheDocument();
    expect(screen.getByText('"Validation error statement"')).toBeInTheDocument();

    // Click Validation Errors filter
    fireEvent.click(screen.getByRole('button', { name: /Validation Errors \(1\)/i }));

    // Now only Validation error claim is rendered
    expect(screen.queryByText('"Supported statement"')).not.toBeInTheDocument();
    expect(screen.getByText('"Validation error statement"')).toBeInTheDocument();

    // Click Supported filter
    fireEvent.click(screen.getByRole('button', { name: /^Supported \(1\)/i }));
    expect(screen.getByText('"Supported statement"')).toBeInTheDocument();
    expect(screen.queryByText('"Validation error statement"')).not.toBeInTheDocument();

    // Click All filter
    fireEvent.click(screen.getByRole('button', { name: /^All \(2\)/i }));
    expect(screen.getByText('"Supported statement"')).toBeInTheDocument();
    expect(screen.getByText('"Validation error statement"')).toBeInTheDocument();
  });

  it('renders empty state when claims array is empty', () => {
    render(<ClaimValidationTable claims={[]} />);
    expect(screen.getByText(/No claims validated for this investigation/i)).toBeInTheDocument();
  });

  it('handles missing or malformed evidence arrays safely without throwing', () => {
    const malformedClaims: any[] = [
      {
        claimId: 'claim-malformed',
        investigationId: 'inv-1',
        statement: 'Claim with undefined evidence arrays',
        status: 'SUPPORTED',
        supportingEvidenceIds: null,
        contradictingEvidenceIds: undefined,
        explanation: 'Safe fallback test',
      },
    ];

    render(<ClaimValidationTable claims={malformedClaims} />);
    expect(screen.getByText('Zero evidence IDs cited')).toBeInTheDocument();
    expect(screen.getByText('None')).toBeInTheDocument();
  });
});
