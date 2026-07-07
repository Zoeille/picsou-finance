import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'
import fs from 'node:fs'

const localCertPath = path.resolve(__dirname, '.local/certs/picsou-local-cert.pem')
const localKeyPath = path.resolve(__dirname, '.local/certs/picsou-local-key.pem')
const localHttps =
  fs.existsSync(localCertPath) && fs.existsSync(localKeyPath)
    ? {
        cert: fs.readFileSync(localCertPath),
        key: fs.readFileSync(localKeyPath),
      }
    : undefined

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
    // Force a single React instance. Without this a transitive dep can resolve
    // its own copy, which surfaces as "dispatcher is null" / "can't access
    // useContext" at runtime (two Reacts → hooks talk to the wrong renderer).
    dedupe: ['react', 'react-dom'],
  },
  optimizeDeps: {
    // Pre-bundle the whole runtime dependency surface on first start. Routes are
    // lazy-loaded, so navigating used to let Vite discover a new dep mid-session,
    // re-optimize, and soft-reload — leaving react and react-dom on mismatched
    // optimize-deps hashes (two React instances → "dispatcher is null"). Listing
    // everything up front makes the first crawl comprehensive and avoids the
    // mid-session re-optimization that triggered the crash.
    include: [
      'react',
      'react-dom',
      'react-dom/client',
      'react/jsx-runtime',
      'react-router-dom',
      'react-i18next',
      'i18next',
      'i18next-browser-languagedetector',
      '@tanstack/react-query',
      'react-hook-form',
      '@hookform/resolvers/zod',
      'axios',
      'zod',
      'zustand',
      'recharts',
      'lucide-react',
      'sonner',
      'next-themes',
      'class-variance-authority',
      'clsx',
      'tailwind-merge',
      'input-otp',
    ],
  },
  server: {
    port: 5173,
    https: localHttps,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
