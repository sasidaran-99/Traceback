import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import App from '../App';

describe('App', () => {
  it('renders application brand, navigation sidebar, and header', () => {
    render(<App />);

    // Brand and subtitle
    expect(screen.getByText('TRACEBACK')).toBeInTheDocument();
    expect(screen.getByText('Autonomous Investigation')).toBeInTheDocument();

    // Nav links
    expect(screen.getByText('Overview')).toBeInTheDocument();
    expect(screen.getAllByText('New Investigation').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('History')).toBeInTheDocument();
    expect(screen.getByText('Settings')).toBeInTheDocument();

    // Hero title in Dashboard
    expect(screen.getByText(/Don't just tell developers that something broke/i)).toBeInTheDocument();
  });
});
