/**
 * Development authentication API client.
 * Communicates strictly with the backend development token issuance endpoint.
 *
 * <p>SECURITY NOTICE:
 * Development authentication is available ONLY when the backend runs in 'dev' or 'local'
 * profiles with dev-auth enabled. In production, this endpoint does not exist.
 * Never send custom identity spoofing headers (X-Actor-Id, X-Actor-Role).
 */

import { apiClient } from './client';
import type { DevPersona, DevTokenRequest, DevTokenResponse } from './types';

export const authApi = {
  /**
   * Requests a genuine signed RS256 JWT for the specified development persona.
   *
   * @param persona One of the 4 predefined personas (ALICE_ADMIN, BOB_ADMIN, CHARLIE_OPERATOR, DANA_VIEWER)
   * @returns DevTokenResponse containing the JWT token, tokenType, expiresIn, issuer, subject, and roles
   */
  async loginWithPersona(persona: DevPersona): Promise<DevTokenResponse> {
    const payload: DevTokenRequest = { persona };
    return apiClient<DevTokenResponse>('/api/dev/auth/token', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
};
