import { useEffect, useState, useCallback } from 'react';
import { investigationApi } from '../api/investigationApi';
import type { HealthResponse } from '../api/types';

export interface HealthState {
  isOnline: boolean;
  isChecking: boolean;
  health: HealthResponse | null;
  error: string | null;
  checkHealth: () => Promise<void>;
}

export function useHealth(): HealthState {
  const [isOnline, setIsOnline] = useState<boolean>(false);
  const [isChecking, setIsChecking] = useState<boolean>(true);
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const checkHealth = useCallback(async () => {
    setIsChecking(true);
    try {
      const data = await investigationApi.getHealth();
      setHealth(data);
      setIsOnline(data.status === 'UP');
      setError(null);
    } catch (err: unknown) {
      setIsOnline(false);
      setHealth(null);
      setError(err instanceof Error ? err.message : 'Backend unreachable');
    } finally {
      setIsChecking(false);
    }
  }, []);

  useEffect(() => {
    checkHealth();
    const interval = setInterval(checkHealth, 20000);
    return () => clearInterval(interval);
  }, [checkHealth]);

  return { isOnline, isChecking, health, error, checkHealth };
}
