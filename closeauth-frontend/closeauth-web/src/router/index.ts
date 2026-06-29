import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/views/public/HomeView.vue'),
    },

    // ── Admin Auth (each view self-wraps in AuthLayout) ──────────────────────
    { path: '/admin/login',           component: () => import('@/views/admin/LoginView.vue') },
    { path: '/admin/register',        component: () => import('@/views/admin/RegisterView.vue') },
    { path: '/admin/forgot-password', component: () => import('@/views/admin/ForgotPasswordView.vue') },
    { path: '/admin/reset-password',  component: () => import('@/views/admin/ResetPasswordView.vue') },

    // ── Admin Portal (AdminLayout, requires auth) ──────────────────────────────
    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: { path: '/admin/dashboard' },
        },
        {
          path: 'dashboard',
          component: () => import('@/views/admin/DashboardView.vue'),
        },
        {
          path: 'clients',
          component: () => import('@/views/admin/ClientsView.vue'),
        },
        {
          path: 'clients/new',
          component: () => import('@/views/admin/ClientCreateView.vue'),
        },
        {
          path: 'users',
          component: () => import('@/views/admin/UsersView.vue'),
        },
        {
          path: 'users/new',
          component: () => import('@/views/admin/UserCreateView.vue'),
        },
        {
          path: 'analytics',
          component: () => import('@/views/admin/AnalyticsView.vue'),
        },
        {
          path: 'security',
          component: () => import('@/views/admin/SecurityView.vue'),
        },
        {
          path: 'settings',
          component: () => import('@/views/admin/SettingsView.vue'),
        },
      ],
    },

    // ── OAuth Flow (OAuthLayout) ───────────────────────────────────────────────
    {
      path: '/oauth',
      component: () => import('@/layouts/OAuthLayout.vue'),
      children: [
        {
          path: '',
          redirect: '/',
        },
        {
          path: 'login',
          component: () => import('@/views/oauth/OAuthLoginView.vue'),
        },
        {
          path: 'register',
          component: () => import('@/views/oauth/OAuthRegisterView.vue'),
        },
        {
          path: 'consent',
          component: () => import('@/views/oauth/OAuthConsentView.vue'),
        },
      ],
    },

    // ── Catch-all: redirect unknown paths to home ─────────────────────────────
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// ── Navigation guard ──────────────────────────────────────────────────────────
router.beforeEach((to) => {
  // Skip auth check in mock mode (no backend needed)
  if (import.meta.env.VITE_MOCK_MODE === 'true') return

  if (to.meta.requiresAuth) {
    const authStore = useAuthStore()
    if (!authStore.isAuthenticated) {
      return { path: '/admin/login', query: { redirect: to.fullPath } }
    }
  }
})

export default router
