import { createRouter, createWebHistory } from 'vue-router'

// TODO(ui-2c/ui-3/ui-4): reintroduce the REST of these route trees once the
// corresponding views are rebuilt against the current backend contract:
//   - src/views/auth/          (UI-2) hosted end-user auth pages — /login
//     (Stage UI-2a) and /register + /verify-email (Stage UI-2b) are wired
//     below; magic-link, reset, consent follow in UI-2c on the same pattern.
//   - src/views/tenant-admin/  (UI-3) tenant-admin dashboard
//   - src/views/platform-admin/(UI-4) minimal platform-admin screens
// Exact URL path conventions (e.g. `/admin/*` vs `/tenant/*`) are deferred to
// the stage that builds each surface — do not over-commit here.
// TENANT_ADMIN and PLATFORM_ADMIN are separate principal types (vision §7.8) —
// keep their route trees/guards separate, don't conflate them.
//
// `/login` here is a CLIENT-SIDE route rendering LoginView.vue — it coexists
// on the same URL path as the BFF's server-side `POST /login` proxy
// (handlers_auth_proxy.go) without conflict: chi only registers that path for
// POST, so a GET here falls through to the SPA catch-all (routes.go's
// `r.NotFound(static.SPAHandler().ServeHTTP)`), which serves this app's
// index.html, and THIS router then matches the path client-side. See the
// stage report for the one open question this leaves (how a real,
// unauthenticated /oauth2/authorize hit ends up navigating a browser to this
// exact path in production — a backend-side wiring question, not something
// this router needs to resolve to be correct on its own terms).
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/views/public/HomeView.vue'),
    },
    {
      path: '/login',
      component: () => import('@/views/auth/LoginView.vue'),
    },
    {
      path: '/register',
      component: () => import('@/views/auth/RegisterView.vue'),
    },
    // Independently reachable (Stage UI-2b, Deliverable 5) — not just an
    // inline step of /register; a user returning later to finish
    // verification (or resend a code) navigates straight here.
    {
      path: '/verify-email',
      component: () => import('@/views/auth/VerifyEmailView.vue'),
    },

    // ── Catch-all: redirect unknown paths to home ─────────────────────────────
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
