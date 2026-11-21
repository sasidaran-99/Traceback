import React from 'react';
import { NavLink, Link } from 'react-router-dom';
import {
  Home,
  LayoutDashboard,
  PlusCircle,
  History,
  Sliders,
} from 'lucide-react';
import { useHealth } from '../../hooks/useHealth';

export const Sidebar: React.FC = () => {
  const { isOnline, isChecking, health } = useHealth();

  const navItems = [
    { to: '/', label: 'Home', icon: Home },
    { to: '/dashboard', label: 'Overview', icon: LayoutDashboard },
    { to: '/new', label: 'New Investigation', icon: PlusCircle },
    { to: '/history', label: 'History', icon: History },
    { to: '/settings', label: 'Settings', icon: Sliders },
  ];

  return (
    <aside className="w-56 bg-[#0E1216] border-r border-[#1F262E] flex flex-col shrink-0 h-screen sticky top-0 select-none z-30 font-sans">
      {/* Brand Header */}
      <Link
        to="/dashboard"
        className="px-4 py-4 border-b border-[#1F262E] flex flex-col justify-center hover:bg-[#141A20]/50 transition-colors"
        title="TraceBack Forensic Workbench"
      >
        <div className="flex items-center gap-2.5">
          <img
            src="/assets/traceback-logo.png"
            alt="TraceBack Logo"
            className="h-7 w-auto object-contain shrink-0"
          />
          <div className="flex flex-col">
            <div className="flex items-center gap-1.5">
              <span className="font-mono font-bold text-sm tracking-wider text-[#E4E8EE]">
                TRACEBACK
              </span>
              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded border border-[#2A323C] bg-[#141A20] text-[#A7B0BC] font-medium leading-none">
                2.0
              </span>
            </div>
            <p className="text-[11px] font-sans text-[#6B7585] mt-0.5 tracking-normal">
              Autonomous Investigation
            </p>
          </div>
        </div>
      </Link>

      {/* Navigation Links */}
      <nav className="p-3 space-y-1 flex-1 overflow-y-auto">
        <div className="px-3 pb-1 text-[10px] font-sans uppercase tracking-wider text-[#6B7585] font-semibold">
          Forensics
        </div>
        {navItems.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/' || item.to === '/dashboard'}
              className={({ isActive }) =>
                `flex items-center gap-3 py-2 rounded-md text-xs font-sans transition-all ${
                  isActive
                    ? 'bg-[#1A2129] text-[#E4E8EE] border-l-2 border-[#7FA3C4] pl-2.5 font-medium shadow-xs'
                    : 'text-[#A7B0BC] hover:text-[#E4E8EE] hover:bg-[#141A20] pl-3'
                }`
              }
            >
              <Icon className="w-4 h-4 shrink-0 text-[#7FA3C4]" />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </nav>

      {/* Compact Status Line - No Boxed Card */}
      <div className="px-4 py-3 border-t border-[#1F262E] bg-[#0E1216] text-xs font-sans select-none">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                isChecking
                  ? 'bg-[#C9A24A]'
                  : isOnline
                  ? 'bg-[#4FA88A]'
                  : 'bg-[#C86A6A]'
              }`}
            />
            <span className="text-[11px] text-[#A7B0BC]">Investigation Engine</span>
          </div>
          <span
            className={`font-mono text-[10px] font-semibold ${
              isOnline ? 'text-[#4FA88A]' : 'text-[#C86A6A]'
            }`}
          >
            {isChecking ? 'SYNCING' : isOnline ? 'ONLINE' : 'OFFLINE'}
          </span>
        </div>
        <div className="flex items-center justify-between text-[10px] font-mono text-[#6B7585] mt-1.5">
          <span className="truncate">{health?.version ? `v${health.version}` : 'v1.0.0-SNAPSHOT'}</span>
          <span>:8080</span>
        </div>
      </div>
    </aside>
  );
};
