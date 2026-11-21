import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { PlusCircle, RefreshCw, Server, AlertCircle, User, KeyRound } from 'lucide-react';
import { useHealth } from '../../hooks/useHealth';
import { useAuth } from '../../context/AuthContext';

export const Header: React.FC = () => {
  const { isOnline, isChecking, checkHealth } = useHealth();
  const { user, isAuthenticated, openPersonaModal } = useAuth();
  const location = useLocation();

  const renderBreadcrumb = () => {
    const path = location.pathname;

    if (path === '/' || path === '/dashboard') {
      return (
        <h1 className="text-sm font-sans font-semibold text-[#E4E8EE] tracking-tight">
          System Overview
        </h1>
      );
    }

    if (path === '/new' || path === '/investigations/new') {
      return (
        <div className="flex items-center gap-2 text-xs font-sans">
          <Link to="/dashboard" className="text-[#6B7585] hover:text-[#A7B0BC] transition-colors">
            Overview
          </Link>
          <span className="text-[#2A323C]">/</span>
          <h1 className="text-sm font-sans font-semibold text-[#E4E8EE] tracking-tight">
            New Investigation
          </h1>
        </div>
      );
    }

    if (path === '/history' || path === '/investigations') {
      return (
        <div className="flex items-center gap-2 text-xs font-sans">
          <Link to="/dashboard" className="text-[#6B7585] hover:text-[#A7B0BC] transition-colors">
            Overview
          </Link>
          <span className="text-[#2A323C]">/</span>
          <h1 className="text-sm font-sans font-semibold text-[#E4E8EE] tracking-tight">
            History
          </h1>
        </div>
      );
    }

    if (path === '/settings') {
      return (
        <div className="flex items-center gap-2 text-xs font-sans">
          <Link to="/dashboard" className="text-[#6B7585] hover:text-[#A7B0BC] transition-colors">
            Overview
          </Link>
          <span className="text-[#2A323C]">/</span>
          <h1 className="text-sm font-sans font-semibold text-[#E4E8EE] tracking-tight">
            Settings
          </h1>
        </div>
      );
    }

    if (path.startsWith('/investigations/')) {
      const invId = path.replace('/investigations/', '').split('/')[0];
      const shortId = invId.length > 8 ? `${invId.substring(0, 8)}...` : invId;
      return (
        <div className="flex items-center gap-2 text-xs font-sans">
          <Link to="/history" className="text-[#6B7585] hover:text-[#A7B0BC] transition-colors">
            History
          </Link>
          <span className="text-[#2A323C]">/</span>
          <h1 className="text-sm font-sans font-semibold text-[#E4E8EE] tracking-tight flex items-center gap-2">
            <span>Dossier</span>
            <span
              className="font-mono text-xs text-[#7FA3C4] px-1.5 py-0.5 rounded bg-[#141A20] border border-[#2A323C] font-normal"
              title={invId}
            >
              {shortId}
            </span>
          </h1>
        </div>
      );
    }

    return (
      <h1 className="text-sm font-sans font-semibold text-[#E4E8EE] tracking-tight">
        Workbench
      </h1>
    );
  };

  return (
    <header className="h-14 border-b border-[#1F262E] bg-[#0E1216]/95 backdrop-blur-md px-6 md:px-8 flex items-center justify-between sticky top-0 z-20 select-none font-sans shrink-0">
      {/* Dynamic Breadcrumb / Page Title */}
      <div className="flex items-center gap-3">
        {renderBreadcrumb()}
      </div>

      {/* Right Utility & Primary Action Controls */}
      <div className="flex items-center gap-3">
        {/* API Connection Indicator */}
        <button
          type="button"
          onClick={() => checkHealth()}
          title={`Backend Engine: ${isOnline ? 'ONLINE' : 'OFFLINE'} on port 8080 (Click to re-ping)`}
          className="flex items-center gap-2 px-2.5 py-1.5 rounded-md border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-xs font-mono text-[#A7B0BC] hover:text-[#E4E8EE] transition-colors focus:outline-none focus:border-[#7FA3C4]"
        >
          <span
            className={`w-1.5 h-1.5 rounded-full ${
              isChecking
                ? 'bg-[#C9A24A]'
                : isOnline
                ? 'bg-[#4FA88A]'
                : 'bg-[#C86A6A]'
            }`}
          />
          <Server className="w-3.5 h-3.5 text-[#6B7585]" />
          <span className="text-[11px] font-medium font-sans">
            {isChecking ? 'SYNCING' : isOnline ? 'API CONNECTED' : 'BACKEND OFFLINE'}
          </span>
          <RefreshCw className={`w-3 h-3 text-[#6B7585] ${isChecking ? 'animate-spin' : ''}`} />
        </button>

        {!isOnline && (
          <div
            title="Spring Boot backend on http://localhost:8080 appears offline."
            className="text-[#C86A6A]"
          >
            <AlertCircle className="w-4 h-4" />
          </div>
        )}

        {/* Dev Persona Authentication Pill */}
        {isAuthenticated && user ? (
          <button
            type="button"
            onClick={openPersonaModal}
            title={`Signed in as ${user.subject} (${user.roles.join(', ')}). Click to switch development persona or sign out.`}
            className="flex items-center gap-2 px-2.5 py-1.5 rounded-md border border-[#2A323C] bg-[#141A20] hover:bg-[#1A2129] text-xs transition-colors focus:outline-none focus:border-[#7FA3C4]"
          >
            <div className="w-1.5 h-1.5 rounded-full bg-[#4FA88A]" />
            <User className="w-3.5 h-3.5 text-[#7FA3C4]" />
            <span className="font-mono text-[11px] text-[#E4E8EE] font-medium">{user.subject}</span>
            <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-[#1A2129] border border-[#2A323C] text-[#C9A24A] font-semibold">
              {user.roles[0] || 'VIEWER'}
            </span>
          </button>
        ) : (
          <button
            type="button"
            onClick={openPersonaModal}
            title="Authenticate with a local development persona (ALICE_ADMIN, BOB_ADMIN, CHARLIE_OPERATOR, DANA_VIEWER)"
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md border border-[#C9A24A]/40 bg-[#C9A24A]/10 hover:bg-[#C9A24A]/20 text-xs font-sans text-[#C9A24A] font-medium transition-colors focus:outline-none"
          >
            <KeyRound className="w-3.5 h-3.5" />
            <span>Sign In (Dev)</span>
          </button>
        )}

        {/* Primary Page Action - Action Blue (on pages other than /new) */}
        {location.pathname !== '/new' && location.pathname !== '/investigations/new' && (
          <Link
            to="/new"
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-sans font-medium bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors shadow-xs"
          >
            <PlusCircle className="w-3.5 h-3.5" />
            <span>New Investigation</span>
          </Link>
        )}
      </div>
    </header>
  );
};
