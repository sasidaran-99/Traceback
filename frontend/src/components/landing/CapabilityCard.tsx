import React from 'react';
import { LucideIcon } from 'lucide-react';

interface CapabilityCardProps {
  icon: LucideIcon;
  title: string;
  description: string;
  tag: string;
  accentColor?: 'amber' | 'blue' | 'violet' | 'green';
}

export const CapabilityCard: React.FC<CapabilityCardProps> = ({
  icon: Icon,
  title,
  description,
  tag,
}) => {
  return (
    <div
      className="group p-6 rounded-xl border border-[#1F262E] bg-[#141A20] hover:border-[#2A323C] hover:bg-[#1A2129]/70 transition-all duration-200 flex flex-col justify-between space-y-4 shadow-sm"
    >
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div className="w-10 h-10 rounded-lg flex items-center justify-center border border-[#2A323C] bg-[#1A2129] text-[#7FA3C4] shadow-xs group-hover:border-[#7FA3C4]/40 transition-colors">
            <Icon className="w-5 h-5" />
          </div>
          <span className="text-[10px] font-mono px-2 py-0.5 rounded border border-[#2A323C] bg-[#1A2129] text-[#7FA3C4] uppercase tracking-wider font-semibold">
            {tag}
          </span>
        </div>

        <h3 className="text-sm font-mono font-bold text-[#E4E8EE] tracking-tight">
          {title}
        </h3>

        <p className="text-xs text-[#A7B0BC] leading-relaxed font-sans">
          {description}
        </p>
      </div>

      <div className="pt-3 border-t border-[#1F262E] text-[11px] font-mono text-[#6B7585] flex items-center justify-between">
        <span>Grounded Architecture</span>
        <span className="text-[#A7B0BC] group-hover:text-[#E4E8EE] transition-colors">
          Deterministic
        </span>
      </div>
    </div>
  );
};
