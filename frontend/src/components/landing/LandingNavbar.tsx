import React from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Terminal } from 'lucide-react';
import { useHealth } from '../../hooks/useHealth';

export const LandingNavbar: React.FC = () => {
  const { isOnline, isChecking } = useHealth();

  return (
    <nav className="sticky top-0 z-50 h-16 border-b border-[#1F262E] bg-[#0E1216]/95 backdrop-blur-md px-6 md:px-12 flex items-center justify-between select-none font-sans">
      {/* Brand Logo */}
      <Link to="/" className="flex items-center gap-3 group">
        <img
          src="/assets/traceback-logo.png"
          alt="TraceBack Logo"
          className="h-8 w-auto object-contain shrink-0"
        />
        <div>
          <div className="flex items-center gap-1.5">
            <span className="font-mono font-bold text-base text-[#E4E8EE] tracking-wider">
              TRACEBACK
            </span>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#1A2129] text-[#A7B0BC] border border-[#2A323C]">
              2.0
            </span>
          </div>
          <p className="text-[10px] font-mono text-[#6B7585] leading-none">
            Autonomous Investigation
          </p>
        </div>
      </Link>

      {/* Nav Anchors - Marketing & Application Separated */}
      <div className="hidden md:flex items-center gap-5 text-xs font-mono text-[#B0B8C4]">
        {/* Marketing Anchors */}
        <div className="flex items-center gap-5">
          <a
            href="#how-it-works"
            className="hover:text-[#E4E8EE] transition-colors py-1"
          >
            How It Works
          </a>
          <a
            href="#capabilities"
            className="hover:text-[#E4E8EE] transition-colors py-1"
          >
            Capabilities
          </a>
          <a
            href="#preview"
            className="hover:text-[#E4E8EE] transition-colors py-1"
          >
            Workbench Preview
          </a>
        </div>

        {/* Separator */}
        <span className="h-4 w-px bg-[#2A323C]" />

        {/* Application Navigation */}
        <div className="flex items-center gap-4 text-[#828D9E]">
          <Link
            to="/dashboard"
            className="hover:text-[#E4E8EE] transition-colors py-1"
          >
            Workspace
          </Link>
          <Link
            to="/history"
            className="hover:text-[#E4E8EE] transition-colors py-1"
          >
            History
          </Link>
          <Link
            to="/settings"
            className="hover:text-[#E4E8EE] transition-colors py-1"
          >
            Settings
          </Link>
        </div>
      </div>

      {/* Right Actions */}
      <div className="flex items-center gap-3">
        {/* Engine Status Indicator - Solid dot, neutral text, no glow */}
        <div
          title={`TraceBack Backend: ${isOnline ? 'ONLINE' : 'OFFLINE'} on port 8080`}
          className="hidden lg:flex items-center gap-2 px-2.5 py-1 rounded-md border border-[#2A323C] bg-[#141A20] font-mono text-[11px]"
        >
          <span
            className={`w-2 h-2 rounded-full ${
              isChecking
                ? 'bg-[#C9A24A]'
                : isOnline
                ? 'bg-[#4FA88A]'
                : 'bg-[#C86A6A]'
            }`}
          />
          <span className="text-[#A7B0BC]">Engine</span>
          <span
            className={`font-semibold ${
              isOnline ? 'text-[#4FA88A]' : 'text-[#C86A6A]'
            }`}
          >
            {isChecking ? 'SYNCING' : isOnline ? 'ONLINE' : 'OFFLINE'}
          </span>
        </div>

        {/* Start Investigation Link */}
        <Link
          to="/new"
          className="hidden sm:inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-mono text-[#A7B0BC] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors"
        >
          <Terminal className="w-3.5 h-3.5 text-[#7FA3C4]" />
          <span>New Investigation</span>
        </Link>

        {/* Primary CTA: Open Workspace - Action Blue */}
        <Link
          to="/dashboard"
          className="inline-flex items-center gap-2 px-4 py-2 rounded-md font-sans text-xs font-medium bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors shadow-xs"
        >
          <span>Open Workspace</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </Link>
      </div>
    </nav>
  );
};
