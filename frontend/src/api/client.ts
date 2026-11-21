/**
 * Centralized API client for communicating with the Traceback Spring Boot backend.
 * Provides bearer token attachment, centralized base URL configuration,
 * typed API errors, and robust abort/timeout management.
 */

export const DEFAULT_API_BASE_URL = 'http://localhost:8080';
export const AUTH_TOKEN_STORAGE_KEY = 'traceback_auth_token';

const BASE_URL = (
  import.meta.env.VITE_API_BASE_URL !== undefined
    ? import.meta.env.VITE_API_BASE_URL
    : DEFAULT_API_BASE_URL
).replace(/\/+$/, '');

export const DEFAULT_TIMEOUT_MS = 45000;
export const AGENT_INVESTIGATION_TIMEOUT_MS = 210000; // 3.5 minutes: accommodates 180s backend Bifrost timeout with safety margin

let tokenGetter: (() => string | null) | null = null;

/**
 * Registers an optional dynamic token getter provider (e.g. from React context).
 */
export function setAuthTokenProvider(provider: (() => string | null) | null): void {
  tokenGetter = provider;
}

/**
 * Retrieves the currently active auth token from memory provider or sessionStorage.
 * Never logs or exposes the token value.
 */
export function getAuthToken(): string | null {
  if (tokenGetter) {
    try {
      const token = tokenGetter();
      if (token) return token;
    } catch {
      // ignore
    }
  }
  try {
    if (typeof sessionStorage !== 'undefined') {
      return sessionStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
    }
  } catch {
    // sessionStorage not accessible
  }
  return null;
}

/**
 * Stores or clears the authentication token in sessionStorage.
 */
export function setAuthToken(token: string | null): void {
  try {
    if (typeof sessionStorage !== 'undefined') {
      if (token) {
        sessionStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
      } else {
        sessionStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      }
    }
  } catch {
    // ignore
  }
}

export class ApiError extends Error {
  public statusCode: number;
  public details?: unknown;

  constructor(message: string, statusCode: number, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.statusCode = statusCode;
    this.details = details;
  }

  get isUnauthorized(): boolean {
    return this.statusCode === 401;
  }

  get isForbidden(): boolean {
    return this.statusCode === 403;
  }

  get isNotFound(): boolean {
    return this.statusCode === 404;
  }

  get isConflict(): boolean {
    return this.statusCode === 409;
  }
}

export async function apiClient<T>(
  endpoint: string,
  options: RequestInit = {},
  timeoutMs = DEFAULT_TIMEOUT_MS
): Promise<T> {
  const url = endpoint.startsWith('http')
    ? endpoint
    : `${BASE_URL}${endpoint.startsWith('/') ? '' : '/'}${endpoint}`;

  const controller = new AbortController();
  let timedOut = false;

  const id = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  const callerSignal = options.signal;
  const onCallerAbort = () => {
    controller.abort();
  };

  if (callerSignal) {
    if (callerSignal.aborted) {
      clearTimeout(id);
      controller.abort();
    } else {
      callerSignal.addEventListener('abort', onCallerAbort, { once: true });
    }
  }

  const cleanup = () => {
    clearTimeout(id);
    if (callerSignal) {
      callerSignal.removeEventListener('abort', onCallerAbort);
    }
  };

  const headers = new Headers(options.headers || {});
  if (!headers.has('Content-Type') && options.body && typeof options.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  // Automatically attach Bearer token if available and not explicitly provided
  if (!headers.has('Authorization')) {
    const token = getAuthToken();
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
  }

  try {
    const response = await fetch(url, {
      ...options,
      headers,
      signal: controller.signal,
    });

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
    if (err instanceof ApiError) {
      throw err;
    }
    if (err instanceof Error && err.name === 'AbortError') {
      if (callerSignal?.aborted && !timedOut) {
        throw new ApiError(`Request to ${endpoint} was aborted by caller`, 499);
      }
      throw new ApiError(`Request to ${endpoint} timed out after ${timeoutMs}ms`, 408);
    }
    throw new ApiError(
      err instanceof Error ? err.message : 'Unknown network failure communicating with Traceback backend',
      0
    );
  } finally {
    cleanup();
  }
}
