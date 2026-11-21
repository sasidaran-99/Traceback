import React from 'react';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'neutral' | 'blue' | 'green' | 'amber' | 'rose' | 'purple' | 'orange';
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
    neutral: 'bg-[#141A20] text-[#A7B0BC] border-[#2A323C]',
    blue: 'bg-[#141A20] text-[#7FA3C4] border-[#7FA3C4]/30',
    green: 'bg-[#141A20] text-[#4FA88A] border-[#4FA88A]/30',
    amber: 'bg-[#141A20] text-[#C9A24A] border-[#C9A24A]/30',
    rose: 'bg-[#141A20] text-[#C86A6A] border-[#C86A6A]/30',
    purple: 'bg-[#141A20] text-[#7FA3C4] border-[#2A323C]',
    orange: 'bg-[#141A20] text-[#C9A24A] border-[#C9A24A]/30',
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
