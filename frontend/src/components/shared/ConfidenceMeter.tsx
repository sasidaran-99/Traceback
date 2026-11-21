import React from 'react';

export interface ConfidenceMeterProps {
  score?: number | null;
  size?: 'sm' | 'md' | 'lg';
  showBar?: boolean;
  showLabel?: boolean;
  className?: string;
}

export const ConfidenceMeter: React.FC<ConfidenceMeterProps> = ({
  score,
  size = 'md',
  showBar = true,
  showLabel = true,
  className = '',
}) => {
  const safeScore = score === null || score === undefined || isNaN(score) ? 0 : Math.max(0, Math.min(1, score));
  const percent = Math.round(safeScore * 100);

  // Muted sequential confidence scale
  const getColorConfig = (p: number) => {
    if (p < 40) {
      return {
        text: 'text-[#7C8796]',
        bar: 'bg-[#6B7585]',
        border: 'border-[#2A323C]',
        label: 'Low / Inconclusive',
      };
    }
    if (p < 70) {
      return {
        text: 'text-[#7FA3C4]',
        bar: 'bg-[#7FA3C4]/70',
        border: 'border-[#2A323C]',
        label: 'Moderate',
      };
    }
    if (p < 90) {
      return {
        text: 'text-[#7FA3C4]',
        bar: 'bg-[#7FA3C4]',
        border: 'border-[#2A323C]',
        label: 'Strong',
      };
    }
    return {
      text: 'text-[#4FA88A]',
      bar: 'bg-[#4FA88A]',
      border: 'border-[#2A323C]',
      label: 'Verified / High',
    };
  };

  const config = getColorConfig(percent);

  const textSizes = {
    sm: 'text-xs',
    md: 'text-xs sm:text-sm font-medium',
    lg: 'text-base font-semibold',
  };

  const barHeights = {
    sm: 'h-1.5',
    md: 'h-2',
    lg: 'h-2.5',
  };

  return (
    <div className={`space-y-1 font-mono ${className}`}>
      <div className="flex items-center justify-between text-xs">
        <span className={`font-bold tabular-nums ${config.text} ${textSizes[size]}`}>
          {percent.toFixed(1)}%
        </span>
        {showLabel && (
          <span className="text-[11px] text-[#A7B0BC] font-sans">
            {config.label}
          </span>
        )}
      </div>

      {showBar && (
        <div
          className={`w-full bg-[#1A2129] rounded-full overflow-hidden border border-[#1F262E] ${barHeights[size]}`}
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div
            className={`${config.bar} ${barHeights[size]} rounded-full transition-all duration-300`}
            style={{ width: `${percent}%` }}
          />
        </div>
      )}
    </div>
  );
};
