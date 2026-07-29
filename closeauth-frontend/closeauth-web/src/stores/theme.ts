import { defineStore } from 'pinia'

// TODO(ui-2): rebuild this store against the current backend's branding
// model. The deleted version was confirmed-dead code — it fetched from a
// stale `/api/oauth/theme` endpoint against a multi-table theme shape that no
// longer exists. Branding is now `tenant_branding`, a single flat row per
// tenant, fetched via the admin API like anything else (vision §7.8, §10.4).
export const useThemeStore = defineStore('theme', () => {
  return {}
})
