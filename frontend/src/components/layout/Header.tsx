import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { PlusCircle, RefreshCw, Server, AlertCircle } from 'lucide-react';
import { useHealth } from '../../hooks/useHealth';

export const Header: React.FC = () => {
  const { isOnline, isChecking, checkHealth } = useHealth();
  const location = useLocation();

  const getPageTitle = (path: string) => {
    if (path === '/') return 'System Overview';
    if (path === '/new') return 'New Failure Investigation';
    if (path === '/history') return 'Investigation History';
    if (path === '/settings') return 'System Configuration & Status';
    if (path.startsWith('/investigations/')) return 'Investigation Evidence Dossier';
    return 'Investigation';
  };

  return (
    <header className="h-14 border-b border-dark-700/80 bg-dark-900/60 backdrop-blur px-6 flex items-center justify-between sticky top-0 z-20">
      {/* Page Title */}
      <div className="flex items-center gap-3">
        <h1 className="text-sm font-semibold text-dark-100 font-mono tracking-tight">
          {getPageTitle(location.pathname)}
        </h1>
      </div>

      {/* Right Actions */}
      <div className="flex items-center gap-3">
        {/* Backend Health Pill */}
        <div
          onClick={() => checkHealth()}
          role="button"
          tabIndex={0}
          title={`Backend: ${isOnline ? 'Connected' : 'Disconnected'} (Click to refresh)`}
          className={`flex items-center gap-2 px-2.5 py-1 rounded-full border text-xs font-mono transition-colors cursor-pointer select-none ${
            isOnline
              ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/20'
              : 'bg-rose-500/10 text-rose-400 border-rose-500/30 hover:bg-rose-500/20'
          }`}
        >
          <Server className="w-3.5 h-3.5" />
          <span>{isOnline ? 'API CONNECTED' : 'BACKEND OFFLINE'}</span>
          <RefreshCw className={`w-3 h-3 text-dark-400 ${isChecking ? 'animate-spin' : ''}`} />
        </div>

        {!isOnline && (
          <div
            title="Spring Boot backend on http://localhost:8080 appears offline. Please ensure 'mvn spring-boot:run' is active."
            className="text-rose-400"
          >
            <AlertCircle className="w-4 h-4" />
          </div>
        )}

        {/* New Investigation CTA */}
        {location.pathname !== '/new' && (
          <Link
            to="/new"
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-brand-amber text-dark-950 hover:bg-amber-400 transition-colors shadow-sm font-mono"
          >
            <PlusCircle className="w-3.5 h-3.5" />
            <span>New Investigation</span>
          </Link>
        )}
      </div>
    </header>
  );
};
