import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/views/public/HomeView.vue'),
    },
    {
      path: '/admin/login',
      component: () => import('@/views/admin/LoginView.vue'),
    },
    {
      path: '/admin/register',
      component: () => import('@/views/admin/RegisterView.vue'),
    },
    {
      path: '/admin/forgot-password',
      component: () => import('@/views/admin/ForgotPasswordView.vue'),
    },
    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
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
    {
      path: '/oauth',
      component: () => import('@/layouts/OAuthLayout.vue'),
      children: [
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
  ],
})

export default router
