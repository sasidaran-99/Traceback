import React from 'react';
import { getStatusBadgeVariant } from '../../utils/formatters';

interface StatusPillProps {
  status: string;
  size?: 'sm' | 'md' | 'lg';
  showDot?: boolean;
  className?: string;
}

export const StatusPill: React.FC<StatusPillProps> = ({
  status,
  size = 'md',
  showDot = true,
  className = '',
}) => {
  const styles = getStatusBadgeVariant(status);

  const sizeClasses = {
    sm: 'text-[11px] px-2 py-0.5',
    md: 'text-xs px-2.5 py-1',
    lg: 'text-sm px-3 py-1.5',
  };

  const dotSize = {
    sm: 'w-1.5 h-1.5',
    md: 'w-2 h-2',
    lg: 'w-2.5 h-2.5',
  };

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full font-mono font-semibold uppercase tracking-wider border ${styles.bg} ${styles.text} ${styles.border} ${sizeClasses[size]} ${className}`}
    >
      {showDot && (
        <span className={`rounded-full ${styles.text.replace('text-', 'bg-')} ${dotSize[size]}`} />
      )}
      {status.replace(/_/g, ' ')}
    </span>
  );
};
