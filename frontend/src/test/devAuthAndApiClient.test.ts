import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { apiClient, ApiError, getAuthToken, setAuthToken, AUTH_TOKEN_STORAGE_KEY } from '../api/client';
import { authApi } from '../api/authApi';
import { decodeJwtClaims, isTokenExpired } from '../utils/jwtUtils';
import type { DevPersona, DevTokenResponse } from '../api/types';

// Helper to create a dummy RS256-like JWT token string with desired claims
function createMockJwt(sub: string, roles: string[], expSecondsFromNow = 900): string {
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const exp = Math.floor(Date.now() / 1000) + expSecondsFromNow;
  const payload = btoa(
    JSON.stringify({
      iss: 'https://auth.traceback.dev',
      sub,
      roles,
      aud: 'traceback-api',
      exp,
      iat: Math.floor(Date.now() / 1000),
    })
  );
  const signature = btoa('mock-crypto-signature');
  return `${header}.${payload}.${signature}`;
}

describe('Stage 4.3.7.1 — Frontend Dev Authentication & API Client', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
    setAuthToken(null);
  });

  afterEach(() => {
    sessionStorage.clear();
    setAuthToken(null);
  });

  describe('Dev Authentication API (authApi.loginWithPersona)', () => {
    const personas: Array<{ persona: DevPersona; sub: string; roles: string[] }> = [
      { persona: 'ALICE_ADMIN', sub: 'alice-admin', roles: ['ADMIN'] },
      { persona: 'BOB_ADMIN', sub: 'bob-admin', roles: ['ADMIN'] },
      { persona: 'CHARLIE_OPERATOR', sub: 'charlie-operator', roles: ['OPERATOR'] },
      { persona: 'DANA_VIEWER', sub: 'dana-viewer', roles: ['VIEWER'] },
    ];

    personas.forEach(({ persona, sub, roles }) => {
      it(`successfully logs in persona ${persona} and receives genuine DTO structure`, async () => {
        const mockJwt = createMockJwt(sub, roles, 900);
        const mockBackendResponse: DevTokenResponse = {
          token: mockJwt,
          tokenType: 'Bearer',
          expiresIn: 900,
          issuer: 'https://auth.traceback.dev',
          subject: sub,
          roles,
        };

        const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
          ok: true,
          status: 200,
          headers: new Headers({ 'content-type': 'application/json' }),
          json: async () => mockBackendResponse,
        } as Response);

        const response = await authApi.loginWithPersona(persona);

        expect(response.token).toBe(mockJwt);
        expect(response.tokenType).toBe('Bearer');
        expect(response.subject).toBe(sub);
        expect(response.roles).toEqual(roles);
        expect(response.issuer).toBe('https://auth.traceback.dev');

        expect(fetchSpy).toHaveBeenCalledWith(
          expect.stringContaining('/api/dev/auth/token'),
          expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ persona }),
          })
        );
      });
    });

    it('handles invalid persona with HTTP 400 Bad Request error', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => ({
          status: 400,
          error: 'Bad Request',
          message: 'Unknown development persona: INVALID_PERSONA',
        }),
      } as Response);

      await expect(
        authApi.loginWithPersona('INVALID_PERSONA' as unknown as DevPersona)
      ).rejects.toThrow(ApiError);
    });

    it('handles proxy or non-loopback rejection with HTTP 403 Forbidden', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => ({
          status: 403,
          error: 'Forbidden',
          message: 'Development token issuance is restricted to direct loopback connections; proxied requests are forbidden',
        }),
      } as Response);

      let caughtError: ApiError | null = null;
      try {
        await authApi.loginWithPersona('ALICE_ADMIN');
      } catch (e) {
        caughtError = e as ApiError;
      }

      expect(caughtError).not.toBeNull();
      expect(caughtError?.statusCode).toBe(403);
      expect(caughtError?.isForbidden).toBe(true);
      expect(caughtError?.message).toContain('proxied requests are forbidden');
    });

    it('handles production endpoint absence with HTTP 404', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => ({
          status: 404,
          error: 'Not Found',
          message: 'No endpoint mapping for /api/dev/auth/token',
        }),
      } as Response);

      let caughtError: ApiError | null = null;
      try {
        await authApi.loginWithPersona('ALICE_ADMIN');
      } catch (e) {
        caughtError = e as ApiError;
      }

      expect(caughtError?.statusCode).toBe(404);
      expect(caughtError?.isNotFound).toBe(true);
    });
  });

  describe('Token Attachment & Session Storage in apiClient', () => {
    it('attaches Bearer token from sessionStorage to API requests automatically', async () => {
      const mockToken = createMockJwt('alice-admin', ['ADMIN']);
      sessionStorage.setItem(AUTH_TOKEN_STORAGE_KEY, mockToken);

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => ({ status: 'success' }),
      } as Response);

      await apiClient('/api/remediations?investigationId=123');

      expect(fetchSpy).toHaveBeenCalledWith(
        expect.stringContaining('/api/remediations?investigationId=123'),
        expect.objectContaining({
          headers: expect.any(Headers),
        })
      );

      const calledHeaders = fetchSpy.mock.calls[0][1]?.headers as Headers;
      expect(calledHeaders.get('Authorization')).toBe(`Bearer ${mockToken}`);
    });

    it('does not attach Authorization header when no token is present', async () => {
      sessionStorage.clear();
      setAuthToken(null);

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => ({ status: 'UP' }),
      } as Response);

      await apiClient('/api/health');

      const calledHeaders = fetchSpy.mock.calls[0][1]?.headers as Headers;
      expect(calledHeaders.get('Authorization')).toBeNull();
    });

    it('respects caller-provided Authorization header and does not overwrite it', async () => {
      const storedToken = 'stored-token-111';
      const customToken = 'custom-override-token-222';
      sessionStorage.setItem(AUTH_TOKEN_STORAGE_KEY, storedToken);

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: async () => ({ ok: true }),
      } as Response);

      await apiClient('/api/test', {
        headers: {
          Authorization: `Bearer ${customToken}`,
        },
      });

      const calledHeaders = fetchSpy.mock.calls[0][1]?.headers as Headers;
      expect(calledHeaders.get('Authorization')).toBe(`Bearer ${customToken}`);
    });

    it('setAuthToken correctly stores and deletes token in sessionStorage', () => {
      setAuthToken('token-abc');
      expect(getAuthToken()).toBe('token-abc');
      expect(sessionStorage.getItem(AUTH_TOKEN_STORAGE_KEY)).toBe('token-abc');

      setAuthToken(null);
      expect(getAuthToken()).toBeNull();
      expect(sessionStorage.getItem(AUTH_TOKEN_STORAGE_KEY)).toBeNull();
    });
  });

  describe('JWT Presentation Claims Decoding & Expiry Detection', () => {
    it('decodes valid JWT claims for UI display without throwing', () => {
      const token = createMockJwt('bob-admin', ['ADMIN', 'OPERATOR'], 600);
      const claims = decodeJwtClaims(token);

      expect(claims).not.toBeNull();
      expect(claims?.sub).toBe('bob-admin');
      expect(claims?.roles).toEqual(['ADMIN', 'OPERATOR']);
      expect(claims?.iss).toBe('https://auth.traceback.dev');
      expect(claims?.aud).toBe('traceback-api');
      expect(claims?.exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
    });

    it('returns null for null, empty, or malformed JWT strings', () => {
      expect(decodeJwtClaims(null)).toBeNull();
      expect(decodeJwtClaims('')).toBeNull();
      expect(decodeJwtClaims('not-a-jwt')).toBeNull();
      expect(decodeJwtClaims('part1.part2')).toBeNull();
      expect(decodeJwtClaims('header.invalid-base64!@#.sig')).toBeNull();
    });

    it('accurately identifies unexpired vs expired tokens', () => {
      const activeToken = createMockJwt('alice-admin', ['ADMIN'], 60);
      expect(isTokenExpired(activeToken)).toBe(false);

      const expiredToken = createMockJwt('alice-admin', ['ADMIN'], -60);
      expect(isTokenExpired(expiredToken)).toBe(true);

      expect(isTokenExpired(null)).toBe(true);
      expect(isTokenExpired('malformed')).toBe(true);
    });
  });

  describe('ApiError Distinction (401 vs 403 vs 404)', () => {
    it('provides isUnauthorized flag on 401', () => {
      const err = new ApiError('Unauthorized', 401);
      expect(err.isUnauthorized).toBe(true);
      expect(err.isForbidden).toBe(false);
      expect(err.isNotFound).toBe(false);
    });

    it('provides isForbidden flag on 403', () => {
      const err = new ApiError('Forbidden', 403);
      expect(err.isUnauthorized).toBe(false);
      expect(err.isForbidden).toBe(true);
      expect(err.isNotFound).toBe(false);
    });

    it('provides isNotFound flag on 404', () => {
      const err = new ApiError('Not Found', 404);
      expect(err.isNotFound).toBe(true);
      expect(err.isUnauthorized).toBe(false);
      expect(err.isForbidden).toBe(false);
    });
  });
});
