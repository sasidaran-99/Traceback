import React, { useState } from 'react';
import { Copy, Check } from 'lucide-react';

export interface TechnicalChipProps {
  value: string;
  label?: string;
  truncateLength?: number;
  copyable?: boolean;
  variant?: 'code' | 'evidence' | 'commit' | 'neutral';
  className?: string;
}

export const TechnicalChip: React.FC<TechnicalChipProps> = ({
  value,
  label,
  truncateLength,
  copyable = true,
  variant = 'neutral',
  className = '',
}) => {
  const [copied, setCopied] = useState(false);

  const displayValue = truncateLength && value.length > truncateLength
    ? `${value.substring(0, truncateLength)}...`
    : value;

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // ignore
    }
  };

  const variantStyles = {
    code: 'bg-[#141A20] text-[#E4E8EE] border-[#2A323C]',
    evidence: 'bg-[#141A20] text-[#7FA3C4] border-[#2A323C]',
    commit: 'bg-[#141A20] text-[#7FA3C4] border-[#2A323C]',
    neutral: 'bg-[#141A20] text-[#A7B0BC] border-[#2A323C]',
  };

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded border font-mono text-xs tabular-nums ${variantStyles[variant]} ${className}`}
      title={value}
    >
      {label && <span className="text-[#6B7585] font-sans font-medium text-[11px]">{label}:</span>}
      <span className="select-all">{displayValue}</span>
      {copyable && (
        <button
          type="button"
          onClick={handleCopy}
          className="text-[#6B7585] hover:text-[#E4E8EE] transition-colors focus:outline-none"
          title={`Copy ${label || 'value'}: ${value}`}
        >
          {copied ? <Check className="w-3 h-3 text-[#4FA88A]" /> : <Copy className="w-3 h-3" />}
        </button>
      )}
    </span>
  );
};
