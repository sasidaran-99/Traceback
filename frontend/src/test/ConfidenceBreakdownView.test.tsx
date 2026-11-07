import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ConfidenceBreakdownView } from '../components/investigation/ConfidenceBreakdownView';
import type { ConfidenceBreakdown } from '../api/types';

describe('ConfidenceBreakdownView', () => {
  it('renders formula and confidence values correctly', () => {
    const mockBreakdown: ConfidenceBreakdown = {
      temporalPoints: 30,
      overlapPoints: 30,
      agreementPoints: 15,
      contradictionPenalty: 0,
      rawScore: 75.0,
      normalizedScore: 0.88,
      scoreReasons: ['Commit authored within 2 minutes of crash', 'Exact stack trace method overlap'],
      penaltyReasons: [],
    };

    render(
      <ConfidenceBreakdownView breakdown={mockBreakdown} overallScore={0.88} />
    );

    // Formula heading
    expect(screen.getByText(/Deterministic Scoring Formula/i)).toBeInTheDocument();
    expect(screen.getByText(/S = T \+ O \+ A - C/i)).toBeInTheDocument();

    // Raw and Normalized scores
    expect(screen.getByText('75.0 pts')).toBeInTheDocument();
    expect(screen.getByText('88.0%')).toBeInTheDocument();

    // Positive scoring factors
    expect(screen.getByText('Commit authored within 2 minutes of crash')).toBeInTheDocument();
    expect(screen.getByText('Exact stack trace method overlap')).toBeInTheDocument();

    // Penalty list
    expect(screen.getByText(/No penalties applied/i)).toBeInTheDocument();

    // Decision-support notice
    expect(screen.getByText(/Decision-Support Notice:/i)).toBeInTheDocument();
  });

  it('renders penalty reasons when contradiction penalty is applied', () => {
    const mockBreakdown: ConfidenceBreakdown = {
      temporalPoints: 20,
      overlapPoints: 0,
      agreementPoints: 0,
      contradictionPenalty: 25,
      rawScore: 0.0,
      normalizedScore: 0.0,
      scoreReasons: ['Pre-failure commit detected'],
      penaltyReasons: ['Documentation-only commit without runtime code changes'],
    };

    render(
      <ConfidenceBreakdownView breakdown={mockBreakdown} overallScore={0.0} />
    );

    expect(screen.getByText('Documentation-only commit without runtime code changes')).toBeInTheDocument();
    expect(screen.getByText('-25 / 30')).toBeInTheDocument();
  });

  it('renders empty fallback when breakdown is null', () => {
    render(<ConfidenceBreakdownView breakdown={null} />);
    expect(screen.getByText(/No confidence breakdown metrics available/i)).toBeInTheDocument();
  });
});
