import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The SPA and the API share one origin (AD-26). In deploy/compose.yaml the backend is
    // http://backend:8080; run on the host, it is localhost.
    proxy: {
      '/api': process.env.VIBEFIX_API_URL ?? 'http://localhost:8080',
    },
    // Docker Desktop does not pass file change events from a Windows folder into a container.
    watch: process.env.VIBEFIX_WATCH_POLLING === 'true' ? { usePolling: true, interval: 300 } : undefined,
  },
})
