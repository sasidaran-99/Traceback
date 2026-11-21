/**
 * Remediation Operation Discovery API client.
 * Communicates with backend remediation endpoints.
 */

import { apiClient } from './client';
import type { RemediationOperationResponse } from './types';

export const remediationApi = {
  /**
   * Discovers remediation operations scoped strictly to a specific investigation.
   *
   * @param investigationId Mandatory UUID string representing the target investigation.
   * @throws Error if investigationId is missing or empty.
   * @returns Array of RemediationOperationResponse DTOs ordered newest-first by the backend.
   */
  async getRemediations(investigationId: string): Promise<RemediationOperationResponse[]> {
    if (!investigationId || !investigationId.trim()) {
      throw new Error('investigationId parameter is mandatory for remediation discovery');
    }

    const params = new URLSearchParams({
      investigationId: investigationId.trim(),
    });

    return apiClient<RemediationOperationResponse[]>(`/api/remediations?${params.toString()}`);
  },
};
