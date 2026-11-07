/**
 * Centralized API client for communicating with the Traceback Spring Boot backend.
 */

const BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '');

export class ApiError extends Error {
  public statusCode: number;
  public details?: unknown;

  constructor(message: string, statusCode: number, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.statusCode = statusCode;
    this.details = details;
  }
}

export async function apiClient<T>(
  endpoint: string,
  options: RequestInit = {},
  timeoutMs = 45000
): Promise<T> {
  const url = endpoint.startsWith('http')
    ? endpoint
    : `${BASE_URL}${endpoint.startsWith('/') ? '' : '/'}${endpoint}`;

  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeoutMs);

  const headers = new Headers(options.headers || {});
  if (!headers.has('Content-Type') && options.body && typeof options.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  try {
    const response = await fetch(url, {
      ...options,
      headers,
      signal: controller.signal,
    });

    clearTimeout(id);

    if (response.status === 204) {
      return {} as T;
    }

    const contentType = response.headers.get('content-type');
    const isJson = contentType && contentType.includes('application/json');
    const body = isJson ? await response.json() : await response.text();

    if (!response.ok) {
      const errorMessage =
        isJson && typeof body === 'object' && body !== null && 'message' in body
          ? (body as { message: string }).message
          : `HTTP ${response.status}: ${response.statusText}`;

      throw new ApiError(errorMessage, response.status, body);
    }

    return body as T;
  } catch (err: unknown) {
    clearTimeout(id);
    if (err instanceof ApiError) {
      throw err;
    }
    if (err instanceof Error && err.name === 'AbortError') {
      throw new ApiError(`Request to ${endpoint} timed out after ${timeoutMs}ms`, 408);
    }
    throw new ApiError(
      err instanceof Error ? err.message : 'Unknown network failure communicating with Traceback backend',
      0
    );
  }
}
