import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiClient, ApiError, DEFAULT_TIMEOUT_MS, AGENT_INVESTIGATION_TIMEOUT_MS } from '../api/client';
import { investigationApi } from '../api/investigationApi';

describe('apiClient', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('performs successful GET request and parses JSON', async () => {
    const mockData = { status: 'UP', timestamp: '2026-10-10T14:30:00Z' };

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => mockData,
    } as Response);

    const res = await apiClient('/api/health');
    expect(res).toEqual(mockData);
  });

  it('throws ApiError on HTTP 404 response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ message: 'Investigation 123 not found' }),
    } as Response);

    await expect(apiClient('/api/investigations/123')).rejects.toThrow(ApiError);
  });

  it('sends POST request with JSON body', async () => {
    const mockResponse = { investigationId: 'inv-456' };
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => mockResponse,
    } as Response);

    const result = await apiClient('/api/investigations/analyze', {
      method: 'POST',
      body: JSON.stringify({ repositoryPath: '/path/to/repo' }),
    });

    expect(result).toEqual(mockResponse);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/investigations/analyze'),
      expect.objectContaining({
        method: 'POST',
      })
    );
  });

  it('exports correct default and agent timeout constants', () => {
    expect(DEFAULT_TIMEOUT_MS).toBe(45000);
    expect(AGENT_INVESTIGATION_TIMEOUT_MS).toBe(210000);
    expect(AGENT_INVESTIGATION_TIMEOUT_MS).toBeGreaterThan(180000); // Exceeds 180s backend Bifrost timeout
  });

  it('aborts with exact timeout message on abort error', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementationOnce((_url, init) => {
      return new Promise((_resolve, reject) => {
        const signal = init?.signal as AbortSignal | undefined;
        if (signal) {
          signal.addEventListener('abort', () => {
            const abortError = new Error('The operation was aborted');
            abortError.name = 'AbortError';
            reject(abortError);
          });
        }
      });
    });

    await expect(apiClient('/api/test-timeout', {}, 10)).rejects.toThrow(
      'Request to /api/test-timeout timed out after 10ms'
    );
  });

  it('startAgentInvestigation uses extended agent timeout by default', async () => {
    const mockReport = { reportId: 'REP-1', investigationId: 'inv-123' };
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => mockReport,
    } as Response);

    await investigationApi.startAgentInvestigation({
      repositoryPath: '/test/repo',
      serviceName: 'order-service',
      environment: 'prod',
    });

    expect(setTimeoutSpy).toHaveBeenCalledWith(expect.any(Function), AGENT_INVESTIGATION_TIMEOUT_MS);
  });

  it('standard API requests use DEFAULT_TIMEOUT_MS (45s)', async () => {
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ status: 'UP' }),
    } as Response);

    await investigationApi.getHealth();

    expect(setTimeoutSpy).toHaveBeenCalledWith(expect.any(Function), DEFAULT_TIMEOUT_MS);
  });

  it('aborts and throws 499 when caller cancels via AbortSignal', async () => {
    const callerController = new AbortController();

    vi.spyOn(globalThis, 'fetch').mockImplementationOnce((_url, init) => {
      return new Promise((_resolve, reject) => {
        const signal = init?.signal as AbortSignal | undefined;
        if (signal) {
          signal.addEventListener('abort', () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            reject(err);
          });
        }
      });
    });

    const promise = apiClient('/api/test-cancel', { signal: callerController.signal }, 10000);
    // Abort from caller side
    callerController.abort();

    await expect(promise).rejects.toThrow('Request to /api/test-cancel was aborted by caller');
  });

  it('aborts immediately when caller provides already-aborted signal', async () => {
    const callerController = new AbortController();
    callerController.abort();

    vi.spyOn(globalThis, 'fetch').mockImplementationOnce((_url, init) => {
      return new Promise((_resolve, reject) => {
        const signal = init?.signal as AbortSignal | undefined;
        if (signal?.aborted) {
          const err = new Error('The operation was aborted');
          err.name = 'AbortError';
          reject(err);
        }
      });
    });

    await expect(
      apiClient('/api/test-already-aborted', { signal: callerController.signal }, 10000)
    ).rejects.toThrow('Request to /api/test-already-aborted was aborted by caller');
  });

  it('cleans up event listener and timer on normal successful request', async () => {
    const callerController = new AbortController();
    const removeEventListenerSpy = vi.spyOn(callerController.signal, 'removeEventListener');
    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ success: true }),
    } as Response);

    const result = await apiClient('/api/test-cleanup', { signal: callerController.signal }, 10000);
    expect(result).toEqual({ success: true });
    expect(removeEventListenerSpy).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(clearTimeoutSpy).toHaveBeenCalled();
  });

  it('aborts and throws 499 when caller cancels while reading a delayed response body', async () => {
    const callerController = new AbortController();

    vi.spyOn(globalThis, 'fetch').mockImplementationOnce((_url, init) => {
      const signal = init?.signal as AbortSignal;
      return Promise.resolve({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: () =>
          new Promise((_resolve, reject) => {
            signal.addEventListener('abort', () => {
              const err = new Error('The operation was aborted');
              err.name = 'AbortError';
              reject(err);
            });
          }),
      } as Response);
    });

    const promise = apiClient('/api/test-body-abort', { signal: callerController.signal }, 10000);
    // Abort after headers have returned but while body is reading
    setTimeout(() => callerController.abort(), 10);

    await expect(promise).rejects.toThrow('Request to /api/test-body-abort was aborted by caller');
  });

  it('times out and throws 408 when internal timeout fires while reading a delayed response body', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementationOnce((_url, init) => {
      const signal = init?.signal as AbortSignal;
      return Promise.resolve({
        ok: true,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: () =>
          new Promise((_resolve, reject) => {
            signal.addEventListener('abort', () => {
              const err = new Error('The operation was aborted');
              err.name = 'AbortError';
              reject(err);
            });
          }),
      } as Response);
    });

    await expect(apiClient('/api/test-body-timeout', {}, 25)).rejects.toThrow(
      'Request to /api/test-body-timeout timed out after 25ms'
    );
  });

  it('cleans up event listener and timer after malformed JSON parse error', async () => {
    const callerController = new AbortController();
    const removeEventListenerSpy = vi.spyOn(callerController.signal, 'removeEventListener');
    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => {
        throw new SyntaxError('Unexpected token < in JSON at position 0');
      },
    } as unknown as Response);

    await expect(
      apiClient('/api/test-malformed-json', { signal: callerController.signal }, 10000)
    ).rejects.toThrow();

    expect(removeEventListenerSpy).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(clearTimeoutSpy).toHaveBeenCalled();
  });

  it('cleans up event listener and timer after HTTP error response', async () => {
    const callerController = new AbortController();
    const removeEventListenerSpy = vi.spyOn(callerController.signal, 'removeEventListener');
    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout');

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ message: 'Server crashed' }),
    } as Response);

    await expect(
      apiClient('/api/test-http-error', { signal: callerController.signal }, 10000)
    ).rejects.toThrow(ApiError);

    expect(removeEventListenerSpy).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(clearTimeoutSpy).toHaveBeenCalled();
  });

  it('does not leak listeners across multiple sequential requests', async () => {
    const callerController = new AbortController();
    const addEventListenerSpy = vi.spyOn(callerController.signal, 'addEventListener');
    const removeEventListenerSpy = vi.spyOn(callerController.signal, 'removeEventListener');

    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ ok: true }),
    } as Response);

    await apiClient('/api/req-1', { signal: callerController.signal });
    await apiClient('/api/req-2', { signal: callerController.signal });
    await apiClient('/api/req-3', { signal: callerController.signal });

    // Each request adds 1 listener and removes 1 listener
    expect(addEventListenerSpy).toHaveBeenCalledTimes(3);
    expect(removeEventListenerSpy).toHaveBeenCalledTimes(3);
  });
});
