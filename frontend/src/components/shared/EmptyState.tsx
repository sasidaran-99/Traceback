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
      className={`flex flex-col items-center justify-center p-8 text-center rounded-xl border border-dashed border-dark-700 bg-dark-900/40 ${className}`}
    >
      <div className="p-3 rounded-xl bg-dark-800 text-dark-400 mb-3 border border-dark-700">
        <Icon className="w-6 h-6" />
      </div>
      <h3 className="text-sm font-semibold text-dark-200 mb-1">{title}</h3>
      <p className="text-xs text-dark-400 max-w-sm mb-4 leading-relaxed">{description}</p>
      {actionLabel && onAction && (
        <button
          onClick={onAction}
          className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium bg-brand-amber text-dark-950 hover:bg-amber-400 transition-colors shadow-sm"
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
};
