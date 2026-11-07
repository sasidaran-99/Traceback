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
        dark: {
          950: '#0B0D11', // Deepest background
          900: '#11141A', // Main background
          850: '#171B22', // Sidebar background
          800: '#1E232B', // Surface / Card background
          750: '#252B35', // Hover surface
          700: '#2E3542', // Subtle borders
          600: '#3E4758', // Muted borders
          500: '#5A657B', // Subdued text
          400: '#8894A8', // Secondary text
          300: '#B0B9C6', // Primary muted
          200: '#D5DAE1', // High contrast
          100: '#F0F3F6', // Pure foreground
        },
        brand: {
          amber: '#F59E0B',
          blue: '#3B82F6',
          emerald: '#10B981',
          rose: '#EF4444',
          purple: '#8B5CF6',
        }
      },
      fontFamily: {
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', '"Liberation Mono"', '"Courier New"', 'monospace'],
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'Roboto', 'sans-serif'],
      }
    },
  },
  plugins: [],
}
