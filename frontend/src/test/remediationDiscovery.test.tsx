import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { remediationApi } from '../api/remediationApi';
import { RemediationOperationsList } from '../components/remediation/RemediationOperationsList';
import { AuthProvider } from '../context/AuthContext';
import type { RemediationOperationResponse } from '../api/types';

const mockOperations: RemediationOperationResponse[] = [
  {
    id: 'e48a1234-abcd-4ef0-9123-56789abcdef0',
    investigationId: 'inv-uuid-1111-2222-3333-444455556666',
    idempotencyKey: 'idem-op-1',
    repositoryPath: null, // Strictly null from backend
    targetRef: 'refs/heads/main',
    expectedOldSha: '0123456789abcdef0123456789abcdef01234567',
    proposedNewSha: 'abcdef0123456789abcdef0123456789abcdef01',
    operationType: 'BRANCH_PATCH_PROMOTION',
    status: 'PERSISTED',
    workerId: 'worker-node-1',
    fencingToken: 42,
    leaseExpiresAt: '2026-11-21T01:00:00Z',
    initiatedBy: 'alice-admin',
    failureClassification: 'NONE',
    failureDetails: null,
    recoveryStatus: null,
    createdAt: '2026-11-21T00:30:00Z',
    updatedAt: '2026-11-21T00:31:00Z',
  },
  {
    id: 'f99b5678-cdef-4123-8456-7890abcdef12',
    investigationId: 'inv-uuid-1111-2222-3333-444455556666',
    idempotencyKey: 'idem-op-2',
    repositoryPath: null, // Strictly null from backend
    targetRef: 'refs/heads/release/v2',
    expectedOldSha: '1111222233334444555566667777888899990000',
    proposedNewSha: '2222333344445555666677778888999900001111',
    operationType: 'ROLLBACK_COMMIT',
    status: 'FAILED',
    workerId: 'worker-node-2',
    fencingToken: 43,
    leaseExpiresAt: '2026-11-21T01:05:00Z',
    initiatedBy: 'charlie-operator',
    failureClassification: 'GIT_IO_FAILURE',
    failureDetails: 'Git repository I/O error: Cannot lock <redacted-path>',
    recoveryStatus: 'RECOVERED_ABORTED',
    createdAt: '2026-11-21T00:35:00Z',
    updatedAt: '2026-11-21T00:36:00Z',
  },
];

describe('Stage 4.3.7.1 — Remediation Operation Discovery', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  describe('remediationApi.getRemediations client contract', () => {
    it('throws immediate validation error if investigationId is missing or empty', async () => {
      await expect(remediationApi.getRemediations('')).rejects.toThrow(
        'investigationId parameter is mandatory'
      );
      await expect(remediationApi.getRemediations('   ')).rejects.toThrow(
        'investigationId parameter is mandatory'
      );
    });

    it('queries GET /api/remediations with URL-encoded investigationId', async () => {
      const invId = '123e4567-e89b-12d3-a456-426614174000';
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => mockOperations,
      } as Response);

      const result = await remediationApi.getRemediations(invId);

      expect(result).toHaveLength(2);
      expect(result).toEqual(mockOperations);
      expect(fetchSpy).toHaveBeenCalledWith(
        expect.stringContaining(`/api/remediations?investigationId=${invId}`),
        expect.anything()
      );
    });
  });

  describe('RemediationOperationsList component rendering', () => {
    const renderWithAuth = (component: React.ReactNode) => {
      return render(<AuthProvider>{component}</AuthProvider>);
    };

    it('renders loading spinner while discovery request is in-flight', () => {
      vi.spyOn(remediationApi, 'getRemediations').mockImplementation(
        () => new Promise(() => {}) // never resolves
      );

      renderWithAuth(<RemediationOperationsList investigationId="inv-123" />);

      expect(screen.getByText(/Querying remediation journal/i)).toBeInTheDocument();
    });

    it('renders empty state when investigation has zero operations', async () => {
      vi.spyOn(remediationApi, 'getRemediations').mockResolvedValueOnce([]);

      renderWithAuth(<RemediationOperationsList investigationId="inv-empty" />);

      await waitFor(() => {
        expect(screen.getByText('No Remediation Operations Found')).toBeInTheDocument();
      });
      expect(
        screen.getByText(/No automated or dual-custody remediation operations/i)
      ).toBeInTheDocument();
    });

    it('renders authentication error banner when backend returns 401', async () => {
      vi.spyOn(remediationApi, 'getRemediations').mockRejectedValueOnce(
        new Error('HTTP 401: Unauthorized')
      );

      renderWithAuth(<RemediationOperationsList investigationId="inv-auth-err" />);

      await waitFor(() => {
        expect(screen.getByText('Authentication Required')).toBeInTheDocument();
      });
      expect(screen.getByText(/Sign In \(Dev Persona\)/i)).toBeInTheDocument();
    });

    it('renders access forbidden error banner when backend returns 403', async () => {
      vi.spyOn(remediationApi, 'getRemediations').mockRejectedValueOnce(
        new Error('HTTP 403: Forbidden')
      );

      renderWithAuth(<RemediationOperationsList investigationId="inv-forbidden-err" />);

      await waitFor(() => {
        expect(screen.getByText('Access Forbidden')).toBeInTheDocument();
      });
      expect(screen.getByText(/Your current identity lacks the necessary roles/i)).toBeInTheDocument();
    });

    it('renders full technical operation list with sanitized details and never exposes repositoryPath', async () => {
      vi.spyOn(remediationApi, 'getRemediations').mockResolvedValueOnce(mockOperations);
      const onCountChange = vi.fn();

      const { container } = renderWithAuth(
        <RemediationOperationsList
          investigationId="inv-uuid-1111-2222-3333-444455556666"
          onCountChange={onCountChange}
        />
      );

      await waitFor(() => {
        expect(screen.getByText('BRANCH_PATCH_PROMOTION')).toBeInTheDocument();
      });

      expect(onCountChange).toHaveBeenCalledWith(2);

      // Verify Operation 1 details
      expect(screen.getByText('PERSISTED')).toBeInTheDocument();
      expect(screen.getByText('refs/heads/main')).toBeInTheDocument();
      expect(screen.getByText('alice-admin')).toBeInTheDocument();
      expect(screen.getByText(/Token: 42/)).toBeInTheDocument();

      // Verify Operation 2 failure details and sanitization badge
      expect(screen.getByText('ROLLBACK_COMMIT')).toBeInTheDocument();
      expect(screen.getByText('FAILED')).toBeInTheDocument();
      expect(screen.getByText('refs/heads/release/v2')).toBeInTheDocument();
      expect(screen.getByText(/Failure Classification: GIT_IO_FAILURE/)).toBeInTheDocument();
      expect(screen.getByText(/Host Path Redacted/)).toBeInTheDocument();
      expect(screen.getByText(/Cannot lock <redacted-path>/)).toBeInTheDocument();
      expect(screen.getByText('RECOVERED_ABORTED')).toBeInTheDocument();

      // SECURITY CRITICAL ASSERTION: repositoryPath must NEVER appear anywhere in DOM
      expect(container.innerHTML).not.toContain('repositoryPath');
      expect(container.innerHTML).not.toContain('C:/');
      expect(container.innerHTML).not.toContain('C:\\');
      expect(container.innerHTML).not.toContain('/home/');
      expect(container.innerHTML).not.toContain('/var/');
    });

    it('refetches operations when retry button is clicked after error', async () => {
      const getSpy = vi
        .spyOn(remediationApi, 'getRemediations')
        .mockRejectedValueOnce(new Error('Network offline'))
        .mockResolvedValueOnce(mockOperations);

      renderWithAuth(<RemediationOperationsList investigationId="inv-retry" />);

      await waitFor(() => {
        expect(screen.getByText('Network offline')).toBeInTheDocument();
      });

      const retryBtn = screen.getByText('Retry Query');
      fireEvent.click(retryBtn);

      await waitFor(() => {
        expect(screen.getByText('BRANCH_PATCH_PROMOTION')).toBeInTheDocument();
      });

      expect(getSpy).toHaveBeenCalledTimes(2);
    });
  });
});
