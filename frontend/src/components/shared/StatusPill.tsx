import React from 'react';
import { VerdictBadge } from './VerdictBadge';

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
  return (
    <VerdictBadge
      verdict={status}
      size={size}
      showIcon={showDot}
      className={className}
    />
  );
};
