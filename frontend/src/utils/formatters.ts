/**
 * Formatting utilities for dates, timestamps, commit hashes, and confidence metrics.
 */

export function formatDate(iso?: string | null): string {
  if (!iso) return 'N/A';
  try {
    const d = new Date(iso);
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(d);
  } catch {
    return iso;
  }
}

export function formatTimeOnly(iso?: string | null): string {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    return d.toLocaleTimeString('en-US', { hour12: false });
  } catch {
    return '';
  }
}

export function formatSha(sha?: string | null): string {
  if (!sha) return 'UNKNOWN';
  if (sha.length <= 8) return sha;
  return sha.substring(0, 7);
}

export function formatConfidence(score?: number | null): string {
  if (score === null || score === undefined || isNaN(score)) return '0.00';
  return (score * 100).toFixed(1) + '%';
}

export function formatDurationMs(start?: string | null, end?: string | null): string {
  if (!start || !end) return '';
  try {
    const ms = new Date(end).getTime() - new Date(start).getTime();
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  } catch {
    return '';
  }
}

export function getStatusBadgeVariant(
  status?: string | null
): { bg: string; text: string; border: string } {
  const norm = (status || '').toUpperCase();
  if (norm === 'CONFIRMED' || norm === 'SUPPORTED' || norm === 'UP') {
    return {
      bg: 'bg-emerald-500/10',
      text: 'text-emerald-400',
      border: 'border-emerald-500/30',
    };
  }
  if (norm === 'PROBABLE' || norm === 'PARTIALLY_SUPPORTED' || norm === 'PROPOSED') {
    return {
      bg: 'bg-amber-500/10',
      text: 'text-amber-400',
      border: 'border-amber-500/30',
    };
  }
  if (norm === 'INSUFFICIENT_EVIDENCE' || norm === 'UNSUPPORTED') {
    return {
      bg: 'bg-slate-500/10',
      text: 'text-slate-400',
      border: 'border-slate-500/30',
    };
  }
  if (norm === 'REJECTED' || norm === 'CONTRADICTED' || norm === 'FAILED' || norm === 'DOWN') {
    return {
      bg: 'bg-rose-500/10',
      text: 'text-rose-400',
      border: 'border-rose-500/30',
    };
  }
  return {
    bg: 'bg-dark-700/50',
    text: 'text-dark-300',
    border: 'border-dark-600',
  };
}
