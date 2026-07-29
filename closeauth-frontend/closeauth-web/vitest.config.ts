import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Stage UI-2a: this project had no test runner at all before this stage (the
// pre-refactor era apparently tested Vue only manually/E2E — nothing to
// inherit). A separate config file (rather than adding a `test` block to
// vite.config.ts) keeps the production build config untouched and avoids
// merging vitest's config types into vite's `defineConfig` return type.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // main.css pulls in Google Fonts/Tailwind at-imports that have no
    // bearing on component logic under test and slow down the CSS pipeline
    // for no benefit in a jsdom environment — components under test never
    // import it directly (Vue SFC <style> blocks don't need it resolved to
    // exercise component logic).
    css: false,
  },
})
