import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
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
    ];

    render(<ClaimValidationTable claims={mockClaims} />);

    // Grounding banner
    expect(screen.getByText('Claim Validation Grounding Engine')).toBeInTheDocument();
    expect(screen.getByText('Supported: 1')).toBeInTheDocument();
    expect(screen.getByText('Unsupported/Contradicted: 1')).toBeInTheDocument();

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
  });

  it('renders empty state when claims array is empty', () => {
    render(<ClaimValidationTable claims={[]} />);
    expect(screen.getByText(/No claims validated for this investigation/i)).toBeInTheDocument();
  });
});
