import React from 'react';
import { LucideIcon } from 'lucide-react';

export interface KPITileProps {
  label: string;
  value: string | number;
  subtitle?: string;
  icon?: LucideIcon;
  iconColor?: string;
  badge?: React.ReactNode;
  className?: string;
}

export const KPITile: React.FC<KPITileProps> = ({
  label,
  value,
  subtitle,
  icon: Icon,
  iconColor = 'text-[#7FA3C4]',
  badge,
  className = '',
}) => {
  return (
    <div
      className={`p-5 rounded-lg border border-[#2A323C] bg-[#141A20] hover:border-[#3D4856] transition-all shadow-xs ${className}`}
    >
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-mono uppercase tracking-wider text-[#A7B0BC] font-semibold">
          {label}
        </span>
        <div className="flex items-center gap-1.5">
          {badge}
          {Icon && <Icon className={`w-4 h-4 ${iconColor}`} />}
        </div>
      </div>

      <div className="text-[36px] sm:text-[40px] font-bold font-mono text-[#E4E8EE] tracking-tight leading-none my-1 tabular-nums">
        {value}
      </div>

      {subtitle && (
        <p className="text-xs font-mono text-[#6B7585] mt-2 truncate">
          {subtitle}
        </p>
      )}
    </div>
  );
};
