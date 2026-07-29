import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [tailwindcss(), vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('error', (err, _req, res) => {
            console.warn('[proxy] backend unavailable:', err.message)
            if ('writeHead' in res && typeof res.writeHead === 'function') {
              res.writeHead(503, { 'Content-Type': 'application/json' })
              res.end(JSON.stringify({ error: 'Backend unavailable' }))
            }
          })
        },
      },
      // Surface 1's root-level (non-/api) BFF routes — /login, /logout
      // (Stage UI-1) and /branding (Stage UI-2a) — proxied here too so
      // `npm run dev` exercises the hosted auth pages end-to-end against a
      // locally running BFF (`go run ./cmd/api`), the same way /api/* already
      // does. This was a pre-existing gap for /login and /logout since UI-1
      // (nothing forwarded them in dev); closed here as part of wiring up the
      // first real hosted page (LoginView) rather than leaving it to a later
      // stage that would rediscover the same gap.
      '/login': { target: 'http://localhost:8080', changeOrigin: true },
      '/logout': { target: 'http://localhost:8080', changeOrigin: true },
      '/branding': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
