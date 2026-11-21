import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { Sidebar } from './components/layout/Sidebar';
import { Header } from './components/layout/Header';
import { LandingPage } from './pages/LandingPage';
import { DashboardPage } from './pages/DashboardPage';
import { NewInvestigationPage } from './pages/NewInvestigationPage';
import { InvestigationDetailPage } from './pages/InvestigationDetailPage';
import { HistoryPage } from './pages/HistoryPage';
import { SettingsPage } from './pages/SettingsPage';
import { AuthProvider } from './context/AuthContext';
import { DevPersonaModal } from './components/auth/DevPersonaModal';

export const WorkbenchLayout: React.FC = () => {
  return (
    <div className="flex h-screen bg-[#0C1014] text-[#E4E8EE] overflow-hidden font-sans">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <Header />
        <main className="flex-1 overflow-y-auto px-6 py-6 md:px-8 md:py-6">
          <Outlet />
        </main>
      </div>
      <DevPersonaModal />
    </div>
  );
};

export const AppContent: React.FC = () => {
  return (
    <AuthProvider>
      <Routes>
        {/* Dedicated Standalone Landing Page */}
        <Route path="/" element={<LandingPage />} />

        {/* Internal Forensic Workbench Shell */}
        <Route element={<WorkbenchLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/new" element={<NewInvestigationPage />} />
          <Route path="/investigations/new" element={<NewInvestigationPage />} />
          <Route path="/investigations/:id" element={<InvestigationDetailPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/investigations" element={<HistoryPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>

        {/* Fallback to Landing Page */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
};

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  );
};

export default App;
