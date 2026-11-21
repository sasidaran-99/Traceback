import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SummaryCard } from '../components/investigation/SummaryCard';

describe('SummaryCard', () => {
  it('renders investigation details and fallback badge when active', () => {
    render(
      <SummaryCard
        investigationId="3ef41c49-ac62-467e-ac8f-eddd3581e154"
        reportId="rep-100"
        serviceName="order-service"
        environment="production"
        status="CONFIRMED"
        summary="HikariCP connection pool starvation triggered by commit e8b39a8"
        primaryRootCause="Commit e8b39a8 reduced maximum pool size to 2 under heavy concurrency"
        createdAt="2026-10-10T14:30:00Z"
        isFallback={true}
      />
    );

    // Header info
    expect(screen.getByText('order-service')).toBeInTheDocument();
    expect(screen.getByText('production')).toBeInTheDocument();
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();

    // Deterministic Fallback badge
    expect(screen.getByText('DETERMINISTIC FALLBACK')).toBeInTheDocument();

    // Summary & Root cause
    expect(
      screen.getByText('HikariCP connection pool starvation triggered by commit e8b39a8')
    ).toBeInTheDocument();
    expect(
      screen.getByText('Commit e8b39a8 reduced maximum pool size to 2 under heavy concurrency')
    ).toBeInTheDocument();

    // Investigation ID
    expect(screen.getByText('3ef41c49-ac62-467e-ac8f-eddd3581e154')).toBeInTheDocument();
  });

  it('renders confirmed TacitIQ working tree forensic dossier with primary root cause, 79 supporting evidence, and single candidate score', () => {
    const rootCause =
      'Working tree filesystem anomaly: Tracked files are missing from disk without any committed deletion in Git history. The process responsible for their disappearance is not established.';

    render(
      <SummaryCard
        investigationId="5c48eb24-a00e-435a-aff8-88879ea113fb"
        reportId="REP-e2803caa"
        serviceName="TacitIQ-EconomicTimes"
        environment="Local Development"
        investigationMode="WORKING_TREE_FORENSICS"
        status="CONFIRMED"
        summary="Working tree anomaly: 79 tracked files missing from disk (present in HEAD d711d65). No committed deletion was identified in Git history. The process responsible for their disappearance is not established."
        primaryRootCause={rootCause}
        createdAt="2026-11-07T22:02:27.736681Z"
        isFallback={false}
        supportingEvidenceCount={79}
        contradictoryEvidenceCount={0}
        candidateScore={0.85}
      />
    );

    // Verdict and Mode
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('WORKING TREE FORENSICS')).toBeInTheDocument();
    expect(screen.getByText('TacitIQ-EconomicTimes')).toBeInTheDocument();
    expect(screen.getByText('Local Development')).toBeInTheDocument();

    // Primary Root Cause
    expect(screen.getByText('Primary Suspected Root Cause')).toBeInTheDocument();
    expect(screen.getByText(rootCause)).toBeInTheDocument();

    // Evidence Counts
    expect(screen.getByText(/Supporting Evidence:/i)).toBeInTheDocument();
    expect(screen.getByText('79 items')).toBeInTheDocument();
    expect(screen.getByText(/Contradictory Evidence:/i)).toBeInTheDocument();
    expect(screen.getByText('0 items')).toBeInTheDocument();

    // Single candidate score rendered; zero fabricated dual scores
    expect(screen.getByText('Candidate Score:')).toBeInTheDocument();
    expect(screen.getByText(/85% \(0\.85\)/)).toBeInTheDocument();
    expect(screen.queryByText('Investigation Score:')).not.toBeInTheDocument();
    expect(screen.queryByText(/Authoritative Evidence Score/i)).not.toBeInTheDocument();
  });
});
