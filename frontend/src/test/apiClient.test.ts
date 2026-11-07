import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiClient, ApiError } from '../api/client';

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
});
