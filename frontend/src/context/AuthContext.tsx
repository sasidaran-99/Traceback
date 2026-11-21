import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import { authApi } from '../api/authApi';
import { getAuthToken, setAuthToken, ApiError } from '../api/client';
import { decodeJwtClaims, isTokenExpired } from '../utils/jwtUtils';
import type { DevPersona, AuthUser } from '../api/types';

export const AUTH_PERSONA_STORAGE_KEY = 'traceback_auth_persona';

export interface AuthContextType {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: (persona: DevPersona) => Promise<void>;
  logout: () => void;
  clearError: () => void;
  isPersonaModalOpen: boolean;
  openPersonaModal: () => void;
  closePersonaModal: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [isPersonaModalOpen, setIsPersonaModalOpen] = useState<boolean>(false);

  // Initialize from sessionStorage on mount
  useEffect(() => {
    try {
      const storedToken = getAuthToken();
      const storedPersona = typeof sessionStorage !== 'undefined'
        ? (sessionStorage.getItem(AUTH_PERSONA_STORAGE_KEY) as DevPersona | null)
        : null;

      if (storedToken && !isTokenExpired(storedToken)) {
        const claims = decodeJwtClaims(storedToken);
        if (claims && claims.sub) {
          setUser({
            persona: storedPersona || undefined,
            subject: claims.sub,
            roles: claims.roles || [],
            issuer: claims.iss || 'https://auth.traceback.dev',
            expiresAt: claims.exp || Math.floor(Date.now() / 1000) + 900,
            rawToken: storedToken,
          });
        } else {
          setAuthToken(null);
          if (typeof sessionStorage !== 'undefined') {
            sessionStorage.removeItem(AUTH_PERSONA_STORAGE_KEY);
          }
        }
      } else if (storedToken) {
        // Token has expired
        setAuthToken(null);
        if (typeof sessionStorage !== 'undefined') {
          sessionStorage.removeItem(AUTH_PERSONA_STORAGE_KEY);
        }
      }
    } catch {
      // Ignore sessionStorage issues
    } finally {
      setIsLoading(false);
    }
  }, []);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  const openPersonaModal = useCallback(() => {
    setIsPersonaModalOpen(true);
  }, []);

  const closePersonaModal = useCallback(() => {
    setIsPersonaModalOpen(false);
    setError(null);
  }, []);

  const login = useCallback(async (persona: DevPersona) => {
    setIsLoading(true);
    setError(null);

    try {
      const response = await authApi.loginWithPersona(persona);

      // Store token in client storage (sessionStorage)
      setAuthToken(response.token);
      try {
        if (typeof sessionStorage !== 'undefined') {
          sessionStorage.setItem(AUTH_PERSONA_STORAGE_KEY, persona);
        }
      } catch {
        // ignore
      }

      // Decode presentation claims safely
      const claims = decodeJwtClaims(response.token);

      setUser({
        persona,
        subject: response.subject,
        roles: response.roles,
        issuer: response.issuer,
        expiresAt: claims?.exp || Math.floor(Date.now() / 1000) + response.expiresIn,
        rawToken: response.token,
      });

      setIsPersonaModalOpen(false);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        if (err.isForbidden) {
          setError(
            'Access forbidden (403): Dev authentication is restricted to loopback connections. Forwarded/proxied requests are rejected.'
          );
        } else if (err.isUnauthorized) {
          setError('Authentication failure (401): Request was rejected by the backend authentication service.');
        } else if (err.statusCode === 404) {
          setError(
            'Endpoint not found (404): Dev authentication bridge is not enabled on the backend. Ensure active profile includes "dev" or "local" and dev-auth is enabled.'
          );
        } else {
          setError(err.message || `Authentication failed with status ${err.statusCode}`);
        }
      } else if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Unknown authentication error occurred');
      }
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(() => {
    setAuthToken(null);
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.removeItem(AUTH_PERSONA_STORAGE_KEY);
      }
    } catch {
      // ignore
    }
    setUser(null);
    setError(null);
  }, []);

  const value = useMemo<AuthContextType>(
    () => ({
      user,
      isAuthenticated: !!user && !isTokenExpired(user.rawToken),
      isLoading,
      error,
      login,
      logout,
      clearError,
      isPersonaModalOpen,
      openPersonaModal,
      closePersonaModal,
    }),
    [user, isLoading, error, login, logout, clearError, isPersonaModalOpen, openPersonaModal, closePersonaModal]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
