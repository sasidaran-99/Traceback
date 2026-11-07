import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { BrowserRouter } from 'react-router-dom';
import { NewInvestigationPage } from '../pages/NewInvestigationPage';

describe('NewInvestigationPage', () => {
  it('renders form inputs and handles preset button clicks', () => {
    render(
      <BrowserRouter>
        <NewInvestigationPage />
      </BrowserRouter>
    );

    // Initial page title
    expect(screen.getByText('Launch Failure Investigation')).toBeInTheDocument();

    // Workflow tabs
    expect(screen.getByText('Workflow A: New Analysis')).toBeInTheDocument();
    expect(screen.getByText('Workflow B: Existing ID')).toBeInTheDocument();

    // Click HikariCP preset
    const presetBtn = screen.getByText('Scenario B: HikariCP Pool Timeout');
    fireEvent.click(presetBtn);

    // Form should populate with preset data
    const repoInput = screen.getByPlaceholderText(/e\.g\. C:\/Users/i) as HTMLInputElement;
    expect(repoInput.value).toContain('TraceBack');

    const logInput = screen.getByPlaceholderText(/2026-10-10 14:15:00\.000/i) as HTMLTextAreaElement;
    expect(logInput.value).toContain('HikariPool');
  });

  it('switches to Workflow B for existing investigation IDs', () => {
    render(
      <BrowserRouter>
        <NewInvestigationPage />
      </BrowserRouter>
    );

    const workflowBBtn = screen.getByText('Workflow B: Existing ID');
    fireEvent.click(workflowBBtn);

    expect(
      screen.getByPlaceholderText('e.g. 3ef41c49-ac62-467e-ac8f-eddd3581e154')
    ).toBeInTheDocument();
  });
});
