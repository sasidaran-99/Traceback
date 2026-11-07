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
});
