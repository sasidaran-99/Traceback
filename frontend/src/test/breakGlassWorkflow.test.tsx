import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { BreakGlassPanel } from '../components/remediation/BreakGlassPanel';
import { BreakGlassProposalModal } from '../components/remediation/BreakGlassProposalModal';
import { BreakGlassActionModal } from '../components/remediation/BreakGlassActionModal';
import { BreakGlassSafetyBanner } from '../components/remediation/BreakGlassSafetyBanner';
import { BreakGlassStatusBadge } from '../components/remediation/BreakGlassStatusBadge';
import { breakGlassApi } from '../api/breakGlassApi';
import { AuthProvider, AUTH_PERSONA_STORAGE_KEY } from '../context/AuthContext';
import { setAuthToken } from '../api/client';
import type {
  RemediationOperationResponse,
  RemediationBreakGlassResponse,
} from '../api/types';

// Helper to create valid JWT token string for test personas
function createMockJwt(sub: string, roles: string[], iss = 'https://auth.traceback.dev'): string {
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = btoa(
    JSON.stringify({
      iss,
      sub,
      roles,
      exp: Math.floor(Date.now() / 1000) + 3600,
    })
  );
  return `${header}.${payload}.mock-signature`;
}

const mockOperationRecoveryRequired: RemediationOperationResponse = {
  id: 'op-recovery-1111-2222-3333-444455556666',
  investigationId: 'inv-1',
  idempotencyKey: 'idem-1',
  repositoryPath: null,
  targetRef: 'refs/heads/main',
  expectedOldSha: '1111111111111111111111111111111111111111',
  proposedNewSha: '2222222222222222222222222222222222222222',
  operationType: 'BRANCH_PATCH_PROMOTION',
  status: 'RECOVERY_REQUIRED',
  fencingToken: 10,
  createdAt: '2026-11-21T00:00:00Z',
  updatedAt: '2026-11-21T00:05:00Z',
};

const mockPendingRequest: RemediationBreakGlassResponse = {
  id: 'req-pending-1111-2222-3333-444455556666',
  operationId: 'op-recovery-1111-2222-3333-444455556666',
  targetRef: 'refs/heads/main',
  expectedOldSha: '1111111111111111111111111111111111111111',
  proposedNewSha: '2222222222222222222222222222222222222222',
  snapshotGitSha: '3333333333333333333333333333333333333333',
  operationFencingToken: 10,
  requestedDecision: 'KEEP_FROZEN',
  status: 'PENDING_SECOND_APPROVAL',
  rationale: 'Retain freeze while forensic analysis verifies commit boundaries',
  evidenceDigest: null,
  evidenceBundleRef: null,
  evidenceBundleVersion: null,
  initiatorIssuer: 'https://auth.traceback.dev',
  initiatorSubject: 'alice-admin',
  createdAt: '2026-11-21T00:10:00Z',
  expiresAt: new Date(Date.now() + 3600000).toISOString(), // 1 hour in future
  approverIssuer: null,
  approverSubject: null,
  resolvedAt: null,
  rejectionReason: null,
  executionNotes: null,
  cancelledByIssuer: null,
  cancelledBySubject: null,
  cancelledAt: null,
  cancellationReason: null,
};

describe('Stage 4.3.7.2 — Dual-Custody Break-Glass UI Workflow', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  const renderWithAuth = (ui: React.ReactElement, sub: string, roles: string[]) => {
    const token = createMockJwt(sub, roles);
    setAuthToken(token);
    sessionStorage.setItem(AUTH_PERSONA_STORAGE_KEY, sub);
    return render(<AuthProvider>{ui}</AuthProvider>);
  };

  describe('1. Proposal Workflow & Role Restrictions', () => {
    it('ADMIN can view and open the proposal modal for RECOVERY_REQUIRED operation', async () => {
      vi.spyOn(breakGlassApi, 'getRequests').mockResolvedValue([]);

      renderWithAuth(
        <BreakGlassPanel operation={mockOperationRecoveryRequired} />,
        'alice-admin',
        ['ROLE_ADMIN']
      );

      await waitFor(() => {
        expect(screen.getByText('Propose Break-Glass Review')).toBeInTheDocument();
      });

      fireEvent.click(screen.getByText('Propose Break-Glass Review'));

      expect(
        screen.getByText('Propose Dual-Custody Break-Glass Decision')
      ).toBeInTheDocument();
      expect(screen.getByText('KEEP_FROZEN')).toBeInTheDocument();
    });

    it('OPERATOR and VIEWER cannot propose and are informed of read-only mode', async () => {
      vi.spyOn(breakGlassApi, 'getRequests').mockResolvedValue([]);

      renderWithAuth(
        <BreakGlassPanel operation={mockOperationRecoveryRequired} />,
        'charlie-operator',
        ['ROLE_OPERATOR']
      );

      await waitFor(() => {
        expect(
          screen.getByText(/ROLE_ADMIN required for break-glass mutations/i)
        ).toBeInTheDocument();
      });

      expect(screen.queryByText('Propose Break-Glass Review')).not.toBeInTheDocument();
    });

    it('validates rationale and submits proposal with real API endpoint', async () => {
      const proposeSpy = vi.spyOn(breakGlassApi, 'proposeRequest').mockResolvedValue({
        ...mockPendingRequest,
        id: 'newly-created-req',
      });

      const handleSuccess = vi.fn();
      const handleClose = vi.fn();

      renderWithAuth(
        <BreakGlassProposalModal
          operation={mockOperationRecoveryRequired}
          isOpen={true}
          onClose={handleClose}
          onSuccess={handleSuccess}
        />,
        'alice-admin',
        ['ROLE_ADMIN']
      );

      const submitBtn = screen.getByRole('button', { name: /Submit Break-Glass Proposal/i });
      fireEvent.click(submitBtn);

      // Should show validation error for empty rationale
      await waitFor(() => {
        expect(screen.getByText(/Proposal rationale is required/i)).toBeInTheDocument();
      });

      const textarea = screen.getByPlaceholderText(/State the forensic or operational justification/i);
      fireEvent.change(textarea, { target: { value: 'Valid justification with more than 10 characters' } });

      fireEvent.click(submitBtn);

      await waitFor(() => {
        expect(proposeSpy).toHaveBeenCalledWith(
          mockOperationRecoveryRequired.id,
          expect.objectContaining({
            rationale: 'Valid justification with more than 10 characters',
          })
        );
        expect(handleSuccess).toHaveBeenCalledTimes(1);
        expect(handleClose).toHaveBeenCalledTimes(1);
      });
    });
  });

  describe('2. Dual-Custody Anti-Self-Approval & Second Admin Finalization', () => {
    it('prevents proposal initiator from approving their own proposal', async () => {
      vi.spyOn(breakGlassApi, 'getRequests').mockResolvedValue([mockPendingRequest]);

      renderWithAuth(
        <BreakGlassPanel operation={mockOperationRecoveryRequired} />,
        'alice-admin', // Same as initiator
        ['ROLE_ADMIN']
      );

      await waitFor(() => {
        expect(
          screen.getByText(/You proposed this review. Anti-self-approval requires a peer administrator/i)
        ).toBeInTheDocument();
      });

      // The finalize button must be disabled for the initiator
      const finalizeBtn = screen.getByRole('button', { name: /Review & Finalize \(KEEP_FROZEN\)/i });
      expect(finalizeBtn).toBeDisabled();
    });

    it('enforces anti-self-approval restriction inside ActionModal if rendered', async () => {
      renderWithAuth(
        <BreakGlassActionModal
          request={mockPendingRequest}
          actionType="FINALIZE"
          isOpen={true}
          onClose={vi.fn()}
          onFinalizeSuccess={vi.fn()}
          onRequestUpdated={vi.fn()}
        />,
        'alice-admin', // Initiator
        ['ROLE_ADMIN']
      );

      expect(
        screen.getByText(/Anti-Self-Approval Restriction/i)
      ).toBeInTheDocument();
      expect(
        screen.getByText(/The backend dual-custody policy strictly prohibits an initiator from finalizing/i)
      ).toBeInTheDocument();

      const confirmBtn = screen.getByRole('button', { name: /Confirm & Finalize: KEEP FROZEN/i });
      expect(confirmBtn).toBeDisabled();
    });

    it('allows a different administrator to review and finalize with KEEP_FROZEN', async () => {
      const finalizeSpy = vi.spyOn(breakGlassApi, 'finalizeKeepFrozen').mockResolvedValue({
        decisionApplied: true,
        gitRecoveryExecuted: false,
        refFrozen: true,
        operationStatus: 'RECOVERY_REQUIRED',
        status: 'EXECUTED_KEPT_FROZEN',
        message: 'Break-glass review executed: target ref remains frozen',
        request: {
          ...mockPendingRequest,
          status: 'EXECUTED_KEPT_FROZEN',
          approverIssuer: 'https://auth.traceback.dev',
          approverSubject: 'bob-admin',
        },
      });

      const handleSuccess = vi.fn();

      renderWithAuth(
        <BreakGlassActionModal
          request={mockPendingRequest}
          actionType="FINALIZE"
          isOpen={true}
          onClose={vi.fn()}
          onFinalizeSuccess={handleSuccess}
          onRequestUpdated={vi.fn()}
        />,
        'bob-admin', // Different administrator
        ['ROLE_ADMIN']
      );

      const confirmBtn = screen.getByRole('button', { name: /Confirm & Finalize: KEEP FROZEN/i });
      expect(confirmBtn).toBeEnabled();

      fireEvent.click(confirmBtn);

      await waitFor(() => {
        expect(finalizeSpy).toHaveBeenCalledWith(
          mockPendingRequest.operationId,
          mockPendingRequest.id,
          { decision: 'KEEP_FROZEN', executionNotes: undefined }
        );
        expect(handleSuccess).toHaveBeenCalledTimes(1);
      });
    });
  });

  describe('3. Critical Safety Language & Authoritative State Display', () => {
    it('renders exact safety affirmations for KEEP_FROZEN without green recovery success', () => {
      render(
        <BreakGlassSafetyBanner
          decisionApplied={true}
          gitRecoveryExecuted={false}
          refFrozen={true}
          operationStatus="RECOVERY_REQUIRED"
          message="Retained target ref freeze"
        />
      );

      // Must affirm that decision was recorded and Git recovery was not performed
      expect(
        screen.getByText(
          'Break-glass decision recorded. The repository ref remains frozen. No Git recovery was performed.'
        )
      ).toBeInTheDocument();

      expect(screen.getByText('Git Recovery Executed')).toBeInTheDocument();
      expect(screen.getAllByText('NO').length).toBeGreaterThanOrEqual(2);

      expect(screen.getByText('YES (Confirmed Frozen)')).toBeInTheDocument();
      expect(screen.getByText('RECOVERY_REQUIRED')).toBeInTheDocument();

      // Must NOT display "Recovery successful"
      expect(screen.queryByText(/Recovery successful/i)).not.toBeInTheDocument();
    });

    it('strictly preserves null refFrozen as UNKNOWN and never converts to false', () => {
      render(
        <BreakGlassSafetyBanner
          decisionApplied={false}
          gitRecoveryExecuted={false}
          refFrozen={null}
          operationStatus="RECOVERY_REQUIRED"
        />
      );

      // Must show UNKNOWN (Unverified), not false or unfrozen
      expect(screen.getByText('UNKNOWN (Unverified)')).toBeInTheDocument();
      expect(screen.queryByText('NO (Unfrozen)')).not.toBeInTheDocument();
    });

    it('handles STALE_INVALIDATED with decisionApplied=false', () => {
      render(
        <BreakGlassSafetyBanner
          decisionApplied={false}
          gitRecoveryExecuted={false}
          refFrozen={false}
          operationStatus="PERSISTED"
          message="Operation is no longer in RECOVERY_REQUIRED"
        />
      );

      expect(screen.getByText('Decision Applied: NO')).toBeInTheDocument();
      expect(screen.getByText('PERSISTED')).toBeInTheDocument();
      expect(screen.getByText('NO (Unfrozen)')).toBeInTheDocument();
    });
  });

  describe('4. Reject and Cancel Flows', () => {
    it('initiator can cancel their pending proposal with reason', async () => {
      const cancelSpy = vi.spyOn(breakGlassApi, 'cancelRequest').mockResolvedValue({
        ...mockPendingRequest,
        status: 'CANCELLED',
      });

      const handleUpdated = vi.fn();

      renderWithAuth(
        <BreakGlassActionModal
          request={mockPendingRequest}
          actionType="CANCEL"
          isOpen={true}
          onClose={vi.fn()}
          onFinalizeSuccess={vi.fn()}
          onRequestUpdated={handleUpdated}
        />,
        'alice-admin', // Initiator
        ['ROLE_ADMIN']
      );

      const textarea = screen.getByPlaceholderText(/Explain why you are withdrawing this proposal/i);
      fireEvent.change(textarea, { target: { value: 'Withdrawing proposal as team resolved issue' } });

      const confirmBtn = screen.getByRole('button', { name: /Confirm Proposal Cancellation/i });
      fireEvent.click(confirmBtn);

      await waitFor(() => {
        expect(cancelSpy).toHaveBeenCalledWith(
          mockPendingRequest.operationId,
          mockPendingRequest.id,
          { cancellationReason: 'Withdrawing proposal as team resolved issue' }
        );
        expect(handleUpdated).toHaveBeenCalledTimes(1);
      });
    });

    it('second administrator can reject pending proposal with reason', async () => {
      const rejectSpy = vi.spyOn(breakGlassApi, 'rejectRequest').mockResolvedValue({
        ...mockPendingRequest,
        status: 'REJECTED',
      });

      const handleUpdated = vi.fn();

      renderWithAuth(
        <BreakGlassActionModal
          request={mockPendingRequest}
          actionType="REJECT"
          isOpen={true}
          onClose={vi.fn()}
          onFinalizeSuccess={vi.fn()}
          onRequestUpdated={handleUpdated}
        />,
        'bob-admin', // Peer admin
        ['ROLE_ADMIN']
      );

      const textarea = screen.getByPlaceholderText(/Explain why this proposal is rejected/i);
      fireEvent.change(textarea, { target: { value: 'Proposal rationale is invalid' } });

      const confirmBtn = screen.getByRole('button', { name: /Confirm Proposal Rejection/i });
      fireEvent.click(confirmBtn);

      await waitFor(() => {
        expect(rejectSpy).toHaveBeenCalledWith(
          mockPendingRequest.operationId,
          mockPendingRequest.id,
          { rejectionReason: 'Proposal rationale is invalid' }
        );
        expect(handleUpdated).toHaveBeenCalledTimes(1);
      });
    });
  });

  describe('5. Status Handling & Expiry Invariant', () => {
    it('renders EXPIRED status badge when returned by backend', () => {
      render(<BreakGlassStatusBadge status="EXPIRED" />);
      expect(screen.getByText('EXPIRED')).toBeInTheDocument();
    });

    it('does NOT forge EXPIRED status solely based on local clock passing', async () => {
      const expiredTimeRequest: RemediationBreakGlassResponse = {
        ...mockPendingRequest,
        expiresAt: '2020-01-01T00:00:00Z', // Long in the past according to local clock
        status: 'PENDING_SECOND_APPROVAL', // But backend still reports PENDING_SECOND_APPROVAL
      };

      vi.spyOn(breakGlassApi, 'getRequests').mockResolvedValue([expiredTimeRequest]);

      renderWithAuth(
        <BreakGlassPanel operation={mockOperationRecoveryRequired} />,
        'alice-admin',
        ['ROLE_ADMIN']
      );

      await waitFor(() => {
        // Status badge must strictly say PENDING_SECOND_APPROVAL
        expect(screen.getAllByText('PENDING_SECOND_APPROVAL').length).toBeGreaterThanOrEqual(1);
        // Notice must advise refreshing rather than fabricating EXPIRED status
        expect(
          screen.getByText(/Local clock indicates expiry time has passed/i)
        ).toBeInTheDocument();
      });
    });
  });

  describe('6. Duplicate Submission & Error Handling', () => {
    it('prevents duplicate submissions while proposal request is in progress', async () => {
      let resolveProposal: (val: RemediationBreakGlassResponse) => void;
      const delayedPromise = new Promise<RemediationBreakGlassResponse>((resolve) => {
        resolveProposal = resolve;
      });

      vi.spyOn(breakGlassApi, 'proposeRequest').mockReturnValue(delayedPromise);

      renderWithAuth(
        <BreakGlassProposalModal
          operation={mockOperationRecoveryRequired}
          isOpen={true}
          onClose={vi.fn()}
          onSuccess={vi.fn()}
        />,
        'alice-admin',
        ['ROLE_ADMIN']
      );

      const textarea = screen.getByPlaceholderText(/State the forensic or operational justification/i);
      fireEvent.change(textarea, { target: { value: 'Valid proposal justification rationale' } });

      const submitBtn = screen.getByRole('button', { name: /Submit Break-Glass Proposal/i });
      fireEvent.click(submitBtn);

      // Submit button should now be disabled and show loading text
      await waitFor(() => {
        expect(screen.getByText(/Submitting Proposal\.\.\./i)).toBeInTheDocument();
      });

      // Attempting another click should not trigger another call
      fireEvent.click(submitBtn);
      expect(breakGlassApi.proposeRequest).toHaveBeenCalledTimes(1);

      // Clean up deferred promise
      resolveProposal!({ ...mockPendingRequest, id: 'req-completed' });
    });

    it('displays error message when breakGlassApi fails with 401 or 403', async () => {
      vi.spyOn(breakGlassApi, 'getRequests').mockRejectedValue(
        new Error('HTTP 403 Forbidden: Insufficient permissions to access break-glass requests')
      );

      renderWithAuth(
        <BreakGlassPanel operation={mockOperationRecoveryRequired} />,
        'alice-admin',
        ['ROLE_ADMIN']
      );

      await waitFor(() => {
        expect(screen.getByText(/Break-Glass Discovery Failure/i)).toBeInTheDocument();
        expect(screen.getByText(/HTTP 403 Forbidden/i)).toBeInTheDocument();
      });
    });
  });
});
