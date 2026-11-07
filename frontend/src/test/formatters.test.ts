import { describe, it, expect } from 'vitest';
import {
  formatConfidence,
  formatDate,
  formatDurationMs,
  formatSha,
  formatTimeOnly,
} from '../utils/formatters';

describe('formatters', () => {
  describe('formatConfidence', () => {
    it('formats confidence as a percentage', () => {
      expect(formatConfidence(0.854)).toBe('85.4%');
      expect(formatConfidence(1.0)).toBe('100.0%');
      expect(formatConfidence(0.0)).toBe('0.0%');
      expect(formatConfidence(undefined)).toBe('0.00');
    });
  });

  describe('formatDurationMs', () => {
    it('formats millisecond durations cleanly from start and end ISO strings', () => {
      const start = '2026-10-10T14:30:00.000Z';
      const end500ms = '2026-10-10T14:30:00.500Z';
      const end1500ms = '2026-10-10T14:30:01.500Z';

      expect(formatDurationMs(start, end500ms)).toBe('500ms');
      expect(formatDurationMs(start, end1500ms)).toBe('1.50s');
      expect(formatDurationMs(undefined, undefined)).toBe('');
    });
  });

  describe('formatSha', () => {
    it('abbreviates sha hashes to 7 chars', () => {
      const sha = 'e8b39a89d472c6ffba6c7102e3a1f4967391abf1';
      expect(formatSha(sha)).toBe('e8b39a8');
      expect(formatSha('short')).toBe('short');
      expect(formatSha(undefined)).toBe('UNKNOWN');
    });
  });

  describe('formatDate and formatTimeOnly', () => {
    it('handles invalid or undefined dates gracefully', () => {
      expect(formatDate(undefined)).toBe('N/A');
      expect(formatTimeOnly(undefined)).toBe('');
      expect(formatDate('invalid-date')).toBe('invalid-date');
    });

    it('formats a valid ISO timestamp', () => {
      const iso = '2026-10-10T14:30:00Z';
      const formatted = formatDate(iso);
      expect(formatted).toContain('2026');
    });
  });
});
