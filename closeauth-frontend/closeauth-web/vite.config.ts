import { fileURLToPath, URL } from 'node:url'

import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'

// FE-6.5: preloads the two Latin-subset variable font files (Geist Sans +
// Geist Mono — the only script this app's copy actually uses; Cyrillic and
// Latin-Extended chunks from the same @fontsource-variable packages are
// left to load lazily, same as today) so the browser fetches them
// immediately rather than discovering them only after main.css parses.
// `font-display: swap` (base.css's own @import) already avoids a blocked
// first paint; this closes the remaining gap — text re-flowing into the
// real face after a visible fallback-font flash.
//
// Runs as `transformIndexHtml`'s POST hook so `ctx.bundle` (the real,
// content-hashed output filenames) is available — hand-writing the hash
// here would break on every dependency bump. `-ext-wght` (Latin Extended)
// deliberately does NOT match `-wght` immediately following `latin`, so it
// isn't preloaded; only the two files this regex intends survive.
function fontPreloadPlugin(): Plugin {
  return {
    name: 'closeauth-font-preload',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        if (!ctx.bundle) return html // dev server — no build output to reference
        const fontFiles = Object.keys(ctx.bundle).filter((file) =>
          /^assets\/geist(-mono)?-latin-wght-normal-.*\.woff2$/.test(file),
        )
        if (fontFiles.length === 0) return html
        const links = fontFiles
          .map(
            (file) =>
              `    <link rel="preload" href="/${file}" as="font" type="font/woff2" crossorigin>`,
          )
          .join('\n')
        return html.replace('</head>', `${links}\n  </head>`)
      },
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [tailwindcss(), vue(), vueDevTools(), fontPreloadPlugin()],
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
      // Stage UI-2b: registration + email verification, same reasoning.
      '/register': { target: 'http://localhost:8080', changeOrigin: true },
      '/verify-email': { target: 'http://localhost:8080', changeOrigin: true },
      // Pre-existing gap (predates Stage UI-3a): authPasswordReset.ts and
      // authMagicLink.ts call these absolute paths, same as every other
      // Surface-1 route above, but neither was ever added to this proxy list.
      '/magic-link': { target: 'http://localhost:8080', changeOrigin: true },
      '/password-reset': { target: 'http://localhost:8080', changeOrigin: true },
      // Stage UI-3a: the tenant-admin console's BFF-owned surface —
      // /t/{slug}/api/** and /t/{slug}/admin/{login,reauth} (both BFF
      // routes; /t/{slug}/console is a client-side SPA route and must NOT be
      // proxied) plus the fixed, non-slug-aware callback.
      '^/t/[^/]+/(api|admin)(/|$)': { target: 'http://localhost:8080', changeOrigin: true },
      '/admin/callback': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
