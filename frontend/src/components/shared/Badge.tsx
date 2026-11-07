import React from 'react';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'neutral' | 'blue' | 'green' | 'amber' | 'rose' | 'purple';
  size?: 'sm' | 'md';
  className?: string;
  mono?: boolean;
}

export const Badge: React.FC<BadgeProps> = ({
  children,
  variant = 'neutral',
  size = 'sm',
  className = '',
  mono = false,
}) => {
  const variantStyles = {
    neutral: 'bg-dark-700/60 text-dark-300 border-dark-600',
    blue: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
    green: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
    amber: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
    rose: 'bg-rose-500/10 text-rose-400 border-rose-500/20',
    purple: 'bg-purple-500/10 text-purple-400 border-purple-500/20',
  };

  const sizeStyles = {
    sm: 'text-xs px-2 py-0.5',
    md: 'text-xs px-2.5 py-1',
  };

  return (
    <span
      className={`inline-flex items-center gap-1 rounded font-medium border ${
        variantStyles[variant]
      } ${sizeStyles[size]} ${mono ? 'font-mono' : ''} ${className}`}
    >
      {children}
    </span>
  );
};
