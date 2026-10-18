/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, 'src'),
      // Be Vietnam Pro's font files, referenced by src/theme/fonts.css (self-hosted, no CDN).
      '@fonts': path.resolve(import.meta.dirname, 'node_modules/@fontsource/be-vietnam-pro/files'),
    },
  },
  server: {
    // The SPA and the API share one origin (AD-26). In deploy/compose.yaml the backend is
    // http://backend:8080; run on the host, it is localhost.
    proxy: {
      '/api': process.env.VIBEFIX_API_URL ?? 'http://localhost:8080',
    },
    // Docker Desktop does not pass file change events from a Windows folder into a container.
    watch: process.env.VIBEFIX_WATCH_POLLING === 'true' ? { usePolling: true, interval: 300 } : undefined,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
