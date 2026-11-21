import React from 'react';
import {
  CheckCircle2,
  AlertTriangle,
  HelpCircle,
  Clock,
  XCircle,
  ShieldAlert,
} from 'lucide-react';
import { formatConfidence } from '../../utils/formatters';

export interface VerdictBadgeProps {
  verdict?: string | null;
  size?: 'sm' | 'md' | 'lg';
  showIcon?: boolean;
  confidence?: number | null;
  showConfidence?: boolean;
  className?: string;
}

export const VerdictBadge: React.FC<VerdictBadgeProps> = ({
  verdict = 'UNKNOWN',
  size = 'md',
  showIcon = true,
  confidence,
  showConfidence = false,
  className = '',
}) => {
  const norm = (verdict || 'UNKNOWN').trim().toUpperCase();

  const getConfig = () => {
    switch (norm) {
      case 'CONFIRMED':
      case 'SUPPORTED':
        return {
          label: norm === 'SUPPORTED' ? 'SUPPORTED' : 'CONFIRMED',
          icon: CheckCircle2,
          bg: 'bg-transparent',
          text: 'text-[#4FA88A]',
          border: 'border-[#4FA88A]/30',
        };
      case 'PROBABLE':
      case 'PARTIALLY_SUPPORTED':
      case 'PROPOSED':
        return {
          label: norm === 'PARTIALLY_SUPPORTED' ? 'PARTIALLY SUPPORTED' : 'PROBABLE',
          icon: AlertTriangle,
          bg: 'bg-transparent',
          text: 'text-[#C9A24A]',
          border: 'border-[#C9A24A]/30',
        };
      case 'INSUFFICIENT_EVIDENCE':
      case 'UNSUPPORTED':
        return {
          label: norm === 'UNSUPPORTED' ? 'UNSUPPORTED' : 'INSUFFICIENT EVIDENCE',
          icon: HelpCircle,
          bg: 'bg-transparent',
          text: 'text-[#7C8796]',
          border: 'border-[#2A323C]',
        };
      case 'REPORT_PENDING':
        return {
          label: 'REPORT PENDING',
          icon: Clock,
          bg: 'bg-transparent',
          text: 'text-[#6E9CC4]',
          border: 'border-[#2A323C]',
        };
      case 'PENDING':
      case 'INVESTIGATING':
        return {
          label: 'PENDING',
          icon: Clock,
          bg: 'bg-transparent',
          text: 'text-[#6E9CC4]',
          border: 'border-[#2A323C]',
        };
      case 'VALIDATION_ERROR':
        return {
          label: 'VALIDATION ERROR',
          icon: XCircle,
          bg: 'bg-transparent',
          text: 'text-[#7C8796]',
          border: 'border-[#2A323C]',
        };
      case 'FAILED':
      case 'CONTRADICTED':
      case 'REJECTED':
      case 'ERROR':
        return {
          label: norm === 'CONTRADICTED' ? 'CONTRADICTED' : 'FAILED',
          icon: XCircle,
          bg: 'bg-transparent',
          text: 'text-[#C86A6A]',
          border: 'border-[#C86A6A]/30',
        };
      default:
        return {
          label: norm.replace(/_/g, ' '),
          icon: ShieldAlert,
          bg: 'bg-transparent',
          text: 'text-[#A7B0BC]',
          border: 'border-[#2A323C]',
        };
    }
  };

  const config = getConfig();
  const Icon = config.icon;

  const sizeClasses = {
    sm: 'text-[11px] px-2 py-0.5 gap-1',
    md: 'text-xs px-2.5 py-1 gap-1.5',
    lg: 'text-sm px-3.5 py-1.5 gap-2',
  };

  const iconSizes = {
    sm: 'w-3 h-3',
    md: 'w-3.5 h-3.5',
    lg: 'w-4 h-4',
  };

  const hasConfidence =
    showConfidence && confidence !== undefined && confidence !== null && !isNaN(confidence);

  return (
    <span
      className={`inline-flex items-center rounded-md font-mono font-medium tracking-wide border transition-colors select-none ${config.bg} ${config.text} ${config.border} ${sizeClasses[size]} ${className}`}
      title={`Verdict: ${config.label}${hasConfidence ? ` (Confidence: ${formatConfidence(confidence)})` : ''}`}
    >
      {showIcon && <Icon className={`${iconSizes[size]} shrink-0`} />}
      <span>{config.label}</span>
      {hasConfidence && (
        <span className="ml-1 pl-1 border-l border-current/30 text-[10px] tabular-nums font-bold opacity-90">
          {formatConfidence(confidence)}
        </span>
      )}
    </span>
  );
};
