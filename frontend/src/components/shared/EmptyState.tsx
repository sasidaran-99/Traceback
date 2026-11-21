import React from 'react';
import { LucideIcon } from 'lucide-react';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  description: string;
  actionLabel?: string;
  onAction?: () => void;
  className?: string;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  icon: Icon,
  title,
  description,
  actionLabel,
  onAction,
  className = '',
}) => {
  return (
    <div
      className={`flex flex-col items-center justify-center p-8 text-center rounded-xl border border-dashed border-[#2A323C] bg-[#141A20] font-sans ${className}`}
    >
      <div className="p-3 rounded-xl bg-[#1A2129] text-[#7FA3C4] mb-3 border border-[#2A323C]">
        <Icon className="w-6 h-6" />
      </div>
      <h3 className="text-sm font-semibold text-[#E4E8EE] mb-1 font-sans">{title}</h3>
      <p className="text-xs text-[#828D9E] max-w-sm mb-4 leading-relaxed font-sans">{description}</p>
      {actionLabel && onAction && (
        <button
          onClick={onAction}
          className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-lg text-xs font-medium bg-[#3A6C96] text-white hover:bg-[#457CAB] transition-colors shadow-xs cursor-pointer font-sans"
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
};
