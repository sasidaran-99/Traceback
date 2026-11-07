import React from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  PlusCircle,
  History,
  Sliders,
  ShieldAlert,
  GitBranch,
} from 'lucide-react';
import { useHealth } from '../../hooks/useHealth';

export const Sidebar: React.FC = () => {
  const { isOnline, isChecking, health } = useHealth();

  const navItems = [
    { to: '/', label: 'Overview', icon: LayoutDashboard },
    { to: '/new', label: 'New Investigation', icon: PlusCircle },
    { to: '/history', label: 'History', icon: History },
    { to: '/settings', label: 'Settings', icon: Sliders },
  ];

  return (
    <aside className="w-64 bg-dark-900 border-r border-dark-700/80 flex flex-col shrink-0 h-screen sticky top-0 select-none">
      {/* Brand Header */}
      <div className="p-4 border-b border-dark-700/80 flex items-center gap-3">
        <div className="w-8 h-8 rounded-lg bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-brand-amber shadow-sm">
          <ShieldAlert className="w-5 h-5" />
        </div>
        <div>
          <div className="flex items-center gap-1.5">
            <span className="font-mono font-bold text-sm text-dark-100 tracking-wider">TRACEBACK</span>
            <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-dark-800 text-brand-amber border border-dark-700">2.0</span>
          </div>
          <p className="text-[11px] text-dark-400">Autonomous Investigation</p>
        </div>
      </div>

      {/* Navigation Links */}
      <nav className="p-3 space-y-1 flex-1 overflow-y-auto">
        <div className="px-3 py-1.5 text-[10px] font-mono uppercase tracking-wider text-dark-500 font-semibold">
          Platform
        </div>
        {navItems.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-dark-800 text-brand-amber border border-dark-700 shadow-sm'
                    : 'text-dark-400 hover:text-dark-200 hover:bg-dark-850'
                }`
              }
            >
              <Icon className="w-4 h-4 shrink-0" />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </nav>

      {/* System Status Footer */}
      <div className="p-3 border-t border-dark-700/80 bg-dark-900/60">
        <div className="p-2.5 rounded-lg border border-dark-700 bg-dark-950/70 text-xs">
          <div className="flex items-center justify-between mb-1.5">
            <span className="text-[11px] text-dark-400 font-medium">Core API Engine</span>
            <div className="flex items-center gap-1.5">
              <span
                className={`w-2 h-2 rounded-full ${
                  isChecking
                    ? 'bg-amber-400 animate-pulse'
                    : isOnline
                    ? 'bg-emerald-400'
                    : 'bg-rose-400'
                }`}
              />
              <span
                className={`font-mono text-[11px] font-semibold ${
                  isOnline ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {isChecking ? 'SYNC' : isOnline ? 'ONLINE' : 'OFFLINE'}
              </span>
            </div>
          </div>
          <div className="flex items-center justify-between text-[10px] font-mono text-dark-500">
            <span className="flex items-center gap-1">
              <GitBranch className="w-3 h-3" />
              {health?.version || '1.0.0-SNAPSHOT'}
            </span>
            <span>PORT 8080</span>
          </div>
        </div>
      </div>
    </aside>
  );
};
