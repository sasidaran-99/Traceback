/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // TraceBack 2.0 Neutral Graphite Design Tokens
        'bg-app': '#0C1014',
        'bg-sidebar': '#0E1216',
        'bg-surface': '#141A20',
        'bg-elevated': '#1A2129',
        'bg-code': '#0C1014',

        'border-subtle': '#1F262E',
        'border-default': '#2A323C',
        'border-strong': '#3D4856',

        'text-primary': '#E4E8EE',
        'text-secondary': '#A7B0BC',
        'text-muted': '#6B7585',
        'text-disabled': '#434D5C',

        // Interaction tokens
        'accent': '#7FA3C4',
        'accent-primary': '#7FA3C4',
        'accent-primary-hover': '#457CAB',
        'accent-secondary': '#7FA3C4',
        'action': '#3A6C96',
        'action-hover': '#457CAB',

        // Semantic status tokens
        'success': '#4FA88A',
        'warning': '#C9A24A',
        'error': '#C86A6A',
        'verdict-insufficient': '#7C8796',
        'verdict-pending': '#6E9CC4',

        // Mapped palettes for backward compatibility and clean forensic styling
        workbench: {
          'bg-app': '#0C1014',
          'bg-sidebar': '#0E1216',
          'bg-surface': '#141A20',
          'bg-elevated': '#1A2129',
          'bg-code': '#0C1014',
          'border-subtle': '#1F262E',
          'border-default': '#2A323C',
          'border-strong': '#3D4856',
        },
        forensic: {
          'text-primary': '#E4E8EE',
          'text-secondary': '#A7B0BC',
          'text-muted': '#6B7585',
          'text-disabled': '#434D5C',
          'text-on-accent': '#0C1014',
          'accent-amber': '#7FA3C4',
          'accent-amber-hover': '#457CAB',
          'accent-steel': '#7FA3C4',
          'accent-violet': '#7FA3C4',
          'status-success': '#4FA88A',
          'status-warning': '#C9A24A',
          'status-error': '#C86A6A',
          'status-info': '#7FA3C4',
          'verdict-confirmed': '#4FA88A',
          'verdict-probable': '#C9A24A',
          'verdict-insufficient': '#7C8796',
          'verdict-pending': '#6E9CC4',
          'verdict-failed': '#C86A6A',
        },
        dark: {
          950: '#0C1014',
          900: '#141A20',
          850: '#0E1216',
          800: '#1A2129',
          750: '#222B35',
          700: '#2A323C',
          600: '#3D4856',
          500: '#434D5C',
          400: '#6B7585',
          300: '#A7B0BC',
          200: '#CCD3DE',
          100: '#E4E8EE',
        },
        brand: {
          amber: '#7FA3C4',
          blue: '#7FA3C4',
          emerald: '#4FA88A',
          rose: '#C86A6A',
          purple: '#7FA3C4',
        }
      },
      fontFamily: {
        mono: ['JetBrains Mono', 'IBM Plex Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'monospace'],
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'Roboto', 'sans-serif'],
      }
    },
  },
  plugins: [],
}
