import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          '-apple-system', 'BlinkMacSystemFont', 'San Francisco', 'Segoe UI',
          'Roboto', 'Helvetica Neue', 'sans-serif',
        ],
      },
      colors: {
        app: '#f5f5f7',
      },
    },
  },
  plugins: [],
} satisfies Config
