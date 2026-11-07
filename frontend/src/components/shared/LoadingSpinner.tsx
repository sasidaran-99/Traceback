import React from 'react';
import { Loader2 } from 'lucide-react';

interface LoadingSpinnerProps {
  label?: string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({
  label,
  size = 'md',
  className = '',
}) => {
  const sizeClasses = {
    sm: 'w-4 h-4',
    md: 'w-6 h-6',
    lg: 'w-8 h-8',
  };

  return (
    <div className={`flex flex-col items-center justify-center p-6 gap-3 text-dark-400 ${className}`}>
      <Loader2 className={`animate-spin text-brand-amber ${sizeClasses[size]}`} />
      {label && <span className="text-xs font-mono text-dark-300">{label}</span>}
    </div>
  );
};
