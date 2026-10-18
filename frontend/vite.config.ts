import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The SPA and the API share one origin, as behind Caddy in deployment (AD-26).
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
