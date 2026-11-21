import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BrowserRouter } from 'react-router-dom';
import { NewInvestigationPage } from '../pages/NewInvestigationPage';
import { investigationApi } from '../api/investigationApi';

vi.mock('../api/investigationApi', () => ({
  investigationApi: {
    startAgentInvestigation: vi.fn(),
  },
}));

describe('NewInvestigationPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

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

    // Mode options
    expect(screen.getByText('Runtime Failure')).toBeInTheDocument();
    expect(screen.getByText('Working-Tree Forensics')).toBeInTheDocument();

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

  it('switches between Runtime Failure and Working-Tree Forensics modes', () => {
    render(
      <BrowserRouter>
        <NewInvestigationPage />
      </BrowserRouter>
    );

    const forensicsBtn = screen.getByText('Working-Tree Forensics');
    fireEvent.click(forensicsBtn);

    expect(screen.getByText('Working-Tree Forensics Mode Active')).toBeInTheDocument();
    expect(
      screen.getByText(/Application logs and stack traces are optional/i)
    ).toBeInTheDocument();
  });

  it('rejects submission in Runtime Failure mode when logs and stack trace are empty', async () => {
    render(
      <BrowserRouter>
        <NewInvestigationPage />
      </BrowserRouter>
    );

    const repoInput = screen.getByPlaceholderText(/e\.g\. C:\/Users/i);
    fireEvent.change(repoInput, { target: { value: 'C:/projects/TraceBack' } });

    const submitBtn = screen.getByRole('button', { name: /Start Investigation/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(
        screen.getByText(/Please provide either log content or a stack trace for runtime failure investigations/i)
      ).toBeInTheDocument();
    });
    expect(investigationApi.startAgentInvestigation).not.toHaveBeenCalled();
  });

  it('accepts submission in Working-Tree Forensics mode without logs or stack trace', async () => {
    vi.mocked(investigationApi.startAgentInvestigation).mockResolvedValue({
      reportId: 'REP-12345678',
      investigationId: '550e8400-e29b-41d4-a716-446655440000',
    } as any);

    render(
      <BrowserRouter>
        <NewInvestigationPage />
      </BrowserRouter>
    );

    // Switch to Working-Tree Forensics mode
    const forensicsBtn = screen.getByText('Working-Tree Forensics');
    fireEvent.click(forensicsBtn);

    const repoInput = screen.getByPlaceholderText(/e\.g\. C:\/Users/i);
    fireEvent.change(repoInput, { target: { value: 'C:/projects/TraceBack' } });

    const submitBtn = screen.getByRole('button', { name: /Start Investigation/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(investigationApi.startAgentInvestigation).toHaveBeenCalledWith(
        expect.objectContaining({
          investigationMode: 'WORKING_TREE_FORENSICS',
          repositoryPath: 'C:/projects/TraceBack',
          serviceName: 'order-service',
          environment: 'production',
        })
      );
    });
  });

  it('displays local repository path validation naturally only when triggered without sticky positioning', async () => {
    render(
      <BrowserRouter>
        <NewInvestigationPage />
      </BrowserRouter>
    );

    // Initial load: validation message should NOT be displayed
    expect(screen.queryByText('Local path is required to start investigation')).not.toBeInTheDocument();

    // Trigger submission without filling repository path
    const submitBtn = screen.getByRole('button', { name: /Start Investigation/i });
    fireEvent.click(submitBtn);

    // Validation message should now appear in the parent form section
    await waitFor(() => {
      const validationMsg = screen.getByText('Local path is required to start investigation');
      expect(validationMsg).toBeInTheDocument();
      // Ensure it is not inside a sticky container
      expect(validationMsg.closest('.sticky')).toBeNull();
    });

    // Provide repository path: validation message should disappear
    const repoInput = screen.getByPlaceholderText(/e\.g\. C:\/Users/i);
    fireEvent.change(repoInput, { target: { value: 'C:/Users/developer/projects/TraceBack' } });

    await waitFor(() => {
      expect(screen.queryByText('Local path is required to start investigation')).not.toBeInTheDocument();
    });
  });
});
