/**
 * Break-Glass Dual-Custody REST API Client.
 * Communicates with backend endpoints governed by RemediationBreakGlassController.
 * Enforces strict typing, parameter validation, and standard HTTP methods.
 */

import { apiClient } from './client';
import type {
  RemediationBreakGlassResponse,
  BreakGlassFinalizationResponse,
  ProposeBreakGlassRequest,
  FinalizeBreakGlassRequest,
  RejectBreakGlassRequest,
  CancelBreakGlassRequest,
} from './types';

export const breakGlassApi = {
  /**
   * Lists all break-glass recovery requests for a specific remediation operation.
   * Scoped to operation UUID.
   */
  async getRequests(operationId: string): Promise<RemediationBreakGlassResponse[]> {
    if (!operationId || !operationId.trim()) {
      throw new Error('operationId parameter is mandatory to fetch break-glass requests');
    }
    return apiClient<RemediationBreakGlassResponse[]>(
      `/api/remediations/${encodeURIComponent(operationId.trim())}/break-glass`
    );
  },

  /**
   * Retrieves a single break-glass request by UUID within an operation scope.
   */
  async getRequest(operationId: string, requestId: string): Promise<RemediationBreakGlassResponse> {
    if (!operationId || !operationId.trim()) {
      throw new Error('operationId parameter is mandatory');
    }
    if (!requestId || !requestId.trim()) {
      throw new Error('requestId parameter is mandatory');
    }
    return apiClient<RemediationBreakGlassResponse>(
      `/api/remediations/${encodeURIComponent(operationId.trim())}/break-glass/${encodeURIComponent(requestId.trim())}`
    );
  },

  /**
   * Proposes a new dual-custody break-glass review request for an operation in RECOVERY_REQUIRED.
   * Requires ROLE_ADMIN. Identity is server-derived from JWT claims.
   */
  async proposeRequest(
    operationId: string,
    payload: ProposeBreakGlassRequest
  ): Promise<RemediationBreakGlassResponse> {
    if (!operationId || !operationId.trim()) {
      throw new Error('operationId parameter is mandatory');
    }
    if (!payload.rationale || payload.rationale.trim().length < 10) {
      throw new Error('Rationale must be at least 10 characters');
    }

    return apiClient<RemediationBreakGlassResponse>(
      `/api/remediations/${encodeURIComponent(operationId.trim())}/break-glass/proposals`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      }
    );
  },

  /**
   * Finalizes a pending break-glass request with KEEP_FROZEN decision by a second administrator.
   * Requires ROLE_ADMIN. Proposer cannot approve their own proposal.
   * KEEP_FROZEN retains target ref freeze; no Git recovery is executed.
   */
  async finalizeKeepFrozen(
    operationId: string,
    requestId: string,
    payload: FinalizeBreakGlassRequest
  ): Promise<BreakGlassFinalizationResponse> {
    if (!operationId || !operationId.trim()) {
      throw new Error('operationId parameter is mandatory');
    }
    if (!requestId || !requestId.trim()) {
      throw new Error('requestId parameter is mandatory');
    }
    if (payload.decision !== 'KEEP_FROZEN') {
      throw new Error('Only KEEP_FROZEN decision is accepted');
    }

    return apiClient<BreakGlassFinalizationResponse>(
      `/api/remediations/${encodeURIComponent(operationId.trim())}/break-glass/${encodeURIComponent(
        requestId.trim()
      )}/finalize`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      }
    );
  },

  /**
   * Rejects a pending break-glass request by a second administrator.
   * Requires ROLE_ADMIN. Proposer cannot reject their own proposal.
   */
  async rejectRequest(
    operationId: string,
    requestId: string,
    payload: RejectBreakGlassRequest
  ): Promise<RemediationBreakGlassResponse> {
    if (!operationId || !operationId.trim()) {
      throw new Error('operationId parameter is mandatory');
    }
    if (!requestId || !requestId.trim()) {
      throw new Error('requestId parameter is mandatory');
    }
    if (!payload.rejectionReason || payload.rejectionReason.trim().length < 5) {
      throw new Error('Rejection reason must be at least 5 characters');
    }

    return apiClient<RemediationBreakGlassResponse>(
      `/api/remediations/${encodeURIComponent(operationId.trim())}/break-glass/${encodeURIComponent(
        requestId.trim()
      )}/reject`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      }
    );
  },

  /**
   * Cancels a pending break-glass request by its proposing administrator.
   * Requires ROLE_ADMIN. Only proposal initiator can cancel.
   */
  async cancelRequest(
    operationId: string,
    requestId: string,
    payload: CancelBreakGlassRequest
  ): Promise<RemediationBreakGlassResponse> {
    if (!operationId || !operationId.trim()) {
      throw new Error('operationId parameter is mandatory');
    }
    if (!requestId || !requestId.trim()) {
      throw new Error('requestId parameter is mandatory');
    }
    if (!payload.cancellationReason || payload.cancellationReason.trim().length < 5) {
      throw new Error('Cancellation reason must be at least 5 characters');
    }

    return apiClient<RemediationBreakGlassResponse>(
      `/api/remediations/${encodeURIComponent(operationId.trim())}/break-glass/${encodeURIComponent(
        requestId.trim()
      )}/cancel`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      }
    );
  },
};
