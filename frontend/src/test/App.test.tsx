import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App, { AppContent } from '../App';

describe('App & Routing', () => {
  it('renders dedicated landing page at root route /', () => {
    render(<App />);

    // Brand and subtitle
    expect(screen.getAllByText('TRACEBACK').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Autonomous Investigation').length).toBeGreaterThanOrEqual(1);

    // Hero title in Landing Page
    expect(screen.getByText('Understand why your software failed.')).toBeInTheDocument();

    // Primary & Secondary CTAs
    expect(screen.getAllByText('Start an Investigation').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Open Workspace').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Explore Dashboard').length).toBeGreaterThanOrEqual(1);

    // Pipeline & Capabilities sections
    expect(screen.getByText('How TraceBack Works')).toBeInTheDocument();
    expect(screen.getByText('Core Capabilities')).toBeInTheDocument();
  });

  it('renders forensic workbench shell and dashboard when navigating to /dashboard', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AppContent />
      </MemoryRouter>
    );

    // Workbench Brand and subtitle
    expect(screen.getByText('TRACEBACK')).toBeInTheDocument();
    expect(screen.getByText('Autonomous Investigation')).toBeInTheDocument();

    // Nav links inside Sidebar in exact order
    expect(screen.getByText('Home')).toBeInTheDocument();
    expect(screen.getByText('Overview')).toBeInTheDocument();
    expect(screen.getAllByText('New Investigation').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('History')).toBeInTheDocument();
    expect(screen.getByText('Settings')).toBeInTheDocument();

    // Verify Home navigation link targets root '/'
    const homeLink = screen.getByText('Home').closest('a');
    expect(homeLink).toHaveAttribute('href', '/');

    // Hero title in Dashboard
    expect(screen.getByText(/Don't just tell developers that something broke/i)).toBeInTheDocument();
  });

  it('navigates back to landing page when clicking Home from within the workbench', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AppContent />
      </MemoryRouter>
    );

    // Click Home nav link
    const homeLink = screen.getByText('Home');
    fireEvent.click(homeLink);

    // Landing page hero is now visible
    expect(screen.getByText('Understand why your software failed.')).toBeInTheDocument();
  });

  it('displays Home navigation item when inside History view', () => {
    render(
      <MemoryRouter initialEntries={['/history']}>
        <AppContent />
      </MemoryRouter>
    );

    const homeLink = screen.getByText('Home').closest('a');
    expect(homeLink).toBeInTheDocument();
    expect(homeLink).toHaveAttribute('href', '/');
  });

  it('displays Home navigation item when inside New Investigation view', () => {
    render(
      <MemoryRouter initialEntries={['/new']}>
        <AppContent />
      </MemoryRouter>
    );

    const homeLink = screen.getByText('Home').closest('a');
    expect(homeLink).toBeInTheDocument();
    expect(homeLink).toHaveAttribute('href', '/');
  });

  it('displays Home navigation item when inside Settings view', () => {
    render(
      <MemoryRouter initialEntries={['/settings']}>
        <AppContent />
      </MemoryRouter>
    );

    const homeLink = screen.getByText('Home').closest('a');
    expect(homeLink).toBeInTheDocument();
    expect(homeLink).toHaveAttribute('href', '/');
  });
});
