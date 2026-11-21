import { describe, it, expect, vi, beforeEach } from 'vitest';
import { breakGlassApi } from '../api/breakGlassApi';
import { setAuthToken } from '../api/client';

describe('Stage 4.3.7.2 — Break-Glass REST API Client Tests', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
    setAuthToken('test-token-jwt');
  });

  describe('breakGlassApi.getRequests', () => {
    it('throws validation error if operationId is missing or blank', async () => {
      await expect(breakGlassApi.getRequests('')).rejects.toThrow(
        'operationId parameter is mandatory'
      );
      await expect(breakGlassApi.getRequests('   ')).rejects.toThrow(
        'operationId parameter is mandatory'
      );
    });

    it('queries GET /api/remediations/{operationId}/break-glass with Bearer token', async () => {
      const opId = '11111111-2222-3333-4444-555555555555';
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => [],
      } as Response);

      const result = await breakGlassApi.getRequests(opId);
      expect(result).toEqual([]);
      expect(fetchSpy).toHaveBeenCalledTimes(1);

      const [calledUrl, options] = fetchSpy.mock.calls[0];
      expect(calledUrl).toContain(`/api/remediations/${opId}/break-glass`);
      const authHeader =
        options?.headers instanceof Headers
          ? options.headers.get('Authorization')
          : (options?.headers as Record<string, string>)?.Authorization;
      expect(authHeader).toBe('Bearer test-token-jwt');
    });
  });

  describe('breakGlassApi.getRequest', () => {
    it('throws validation error if operationId or requestId is missing or blank', async () => {
      await expect(breakGlassApi.getRequest('', 'req-1')).rejects.toThrow(
        'operationId parameter is mandatory'
      );
      await expect(breakGlassApi.getRequest('op-1', '')).rejects.toThrow(
        'requestId parameter is mandatory'
      );
    });

    it('queries GET /api/remediations/{operationId}/break-glass/{requestId}', async () => {
      const opId = 'op-uuid-1';
      const reqId = 'req-uuid-1';
      const mockResponse = { id: reqId, operationId: opId, status: 'PENDING_SECOND_APPROVAL' };

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => mockResponse,
      } as Response);

      const result = await breakGlassApi.getRequest(opId, reqId);
      expect(result).toEqual(mockResponse);
      expect(fetchSpy).toHaveBeenCalledWith(
        expect.stringContaining(`/api/remediations/${opId}/break-glass/${reqId}`),
        expect.anything()
      );
    });
  });

  describe('breakGlassApi.proposeRequest', () => {
    it('validates mandatory parameters and rationale length', async () => {
      await expect(
        breakGlassApi.proposeRequest('', { rationale: 'Valid rationale here' })
      ).rejects.toThrow('operationId parameter is mandatory');

      await expect(
        breakGlassApi.proposeRequest('op-1', { rationale: 'Too short' })
      ).rejects.toThrow('Rationale must be at least 10 characters');
    });

    it('submits POST /api/remediations/{operationId}/break-glass/proposals with JSON payload', async () => {
      const opId = 'op-uuid-1';
      const payload = {
        rationale: 'Valid forensic rationale retaining ref lock',
        evidenceDigest: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        evidenceBundleRef: 's3://audit/bundle.tar.gz',
        evidenceBundleVersion: 'v1.0.0',
      };

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => ({ id: 'new-req-id', ...payload }),
      } as Response);

      const result = await breakGlassApi.proposeRequest(opId, payload);
      expect(result.id).toBe('new-req-id');
      expect(fetchSpy).toHaveBeenCalledTimes(1);

      const [calledUrl, options] = fetchSpy.mock.calls[0];
      expect(calledUrl).toContain(`/api/remediations/${opId}/break-glass/proposals`);
      expect(options?.method).toBe('POST');
      expect(JSON.parse(options?.body as string)).toEqual(payload);
    });
  });

  describe('breakGlassApi.finalizeKeepFrozen', () => {
    it('enforces KEEP_FROZEN decision and parameter presence', async () => {
      await expect(
        breakGlassApi.finalizeKeepFrozen('', 'req-1', { decision: 'KEEP_FROZEN' })
      ).rejects.toThrow('operationId parameter is mandatory');

      await expect(
        breakGlassApi.finalizeKeepFrozen('op-1', '', { decision: 'KEEP_FROZEN' })
      ).rejects.toThrow('requestId parameter is mandatory');

      await expect(
        // @ts-expect-error test invalid decision
        breakGlassApi.finalizeKeepFrozen('op-1', 'req-1', { decision: 'CONFIRM_RECOVERY' })
      ).rejects.toThrow('Only KEEP_FROZEN decision is accepted');
    });

    it('submits POST /api/remediations/{operationId}/break-glass/{requestId}/finalize', async () => {
      const opId = 'op-1';
      const reqId = 'req-1';
      const payload = { decision: 'KEEP_FROZEN' as const, executionNotes: 'Audit notes' };

      const mockOutcome = {
        decisionApplied: true,
        gitRecoveryExecuted: false,
        refFrozen: true,
        operationStatus: 'RECOVERY_REQUIRED',
        status: 'EXECUTED_KEPT_FROZEN',
        message: 'Finalized successfully',
      };

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => mockOutcome,
      } as Response);

      const result = await breakGlassApi.finalizeKeepFrozen(opId, reqId, payload);
      expect(result).toEqual(mockOutcome);

      const [calledUrl, options] = fetchSpy.mock.calls[0];
      expect(calledUrl).toContain(`/api/remediations/${opId}/break-glass/${reqId}/finalize`);
      expect(options?.method).toBe('POST');
      expect(JSON.parse(options?.body as string)).toEqual(payload);
    });
  });

  describe('breakGlassApi.rejectRequest', () => {
    it('validates rejection reason length', async () => {
      await expect(
        breakGlassApi.rejectRequest('op-1', 'req-1', { rejectionReason: 'No' })
      ).rejects.toThrow('Rejection reason must be at least 5 characters');
    });

    it('submits POST /api/remediations/{operationId}/break-glass/{requestId}/reject', async () => {
      const opId = 'op-1';
      const reqId = 'req-1';
      const payload = { rejectionReason: 'Proposal is insufficiently justified' };

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => ({ id: reqId, status: 'REJECTED' }),
      } as Response);

      const result = await breakGlassApi.rejectRequest(opId, reqId, payload);
      expect(result.status).toBe('REJECTED');

      const [calledUrl, options] = fetchSpy.mock.calls[0];
      expect(calledUrl).toContain(`/api/remediations/${opId}/break-glass/${reqId}/reject`);
      expect(options?.method).toBe('POST');
    });
  });

  describe('breakGlassApi.cancelRequest', () => {
    it('validates cancellation reason length', async () => {
      await expect(
        breakGlassApi.cancelRequest('op-1', 'req-1', { cancellationReason: 'No' })
      ).rejects.toThrow('Cancellation reason must be at least 5 characters');
    });

    it('submits POST /api/remediations/{operationId}/break-glass/{requestId}/cancel', async () => {
      const opId = 'op-1';
      const reqId = 'req-1';
      const payload = { cancellationReason: 'Withdrawing proposal due to updated forensics' };

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => ({ id: reqId, status: 'CANCELLED' }),
      } as Response);

      const result = await breakGlassApi.cancelRequest(opId, reqId, payload);
      expect(result.status).toBe('CANCELLED');

      const [calledUrl, options] = fetchSpy.mock.calls[0];
      expect(calledUrl).toContain(`/api/remediations/${opId}/break-glass/${reqId}/cancel`);
      expect(options?.method).toBe('POST');
    });
  });
});
