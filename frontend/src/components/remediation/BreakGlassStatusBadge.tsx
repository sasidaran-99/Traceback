import React from 'react';
import {
  Clock,
  Lock,
  XCircle,
  AlertCircle,
  MinusCircle,
  AlertTriangle,
} from 'lucide-react';
import type { RemediationBreakGlassStatus } from '../../api/types';

interface BreakGlassStatusBadgeProps {
  status: RemediationBreakGlassStatus;
  className?: string;
}

export const BreakGlassStatusBadge: React.FC<BreakGlassStatusBadgeProps> = ({
  status,
  className = '',
}) => {
  switch (status) {
    case 'PENDING_SECOND_APPROVAL':
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#C9A24A]/10 border border-[#C9A24A]/40 text-[#C9A24A] ${className}`}
        >
          <Clock className="w-3 h-3 text-[#C9A24A] animate-pulse" />
          <span>PENDING_SECOND_APPROVAL</span>
        </span>
      );

    case 'EXECUTED_KEPT_FROZEN':
      // Deliberately uses a solemn indigo/slate security theme, NOT a green "Recovery successful" badge
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#3A6C96]/20 border border-[#3A6C96]/50 text-[#7FA3C4] ${className}`}
        >
          <Lock className="w-3 h-3 text-[#7FA3C4]" />
          <span>EXECUTED_KEPT_FROZEN</span>
        </span>
      );

    case 'REJECTED':
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#C86A6A]/10 border border-[#C86A6A]/40 text-[#C86A6A] ${className}`}
        >
          <XCircle className="w-3 h-3 text-[#C86A6A]" />
          <span>REJECTED</span>
        </span>
      );

    case 'EXPIRED':
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#2A323C]/50 border border-[#2A323C] text-[#A7B0BC] ${className}`}
        >
          <AlertCircle className="w-3 h-3 text-[#6B7585]" />
          <span>EXPIRED</span>
        </span>
      );

    case 'CANCELLED':
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#2A323C]/50 border border-[#2A323C] text-[#A7B0BC] ${className}`}
        >
          <MinusCircle className="w-3 h-3 text-[#6B7585]" />
          <span>CANCELLED</span>
        </span>
      );

    case 'STALE_INVALIDATED':
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#C9A24A]/15 border border-[#C9A24A]/40 text-[#C9A24A] ${className}`}
        >
          <AlertTriangle className="w-3 h-3 text-[#C9A24A]" />
          <span>STALE_INVALIDATED</span>
        </span>
      );

    default:
      return (
        <span
          className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium bg-[#2A323C]/50 border border-[#2A323C] text-[#A7B0BC] ${className}`}
        >
          <span>{status}</span>
        </span>
      );
  }
};
