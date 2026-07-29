// Static marketing copy for the public landing page (HomeView.vue).
//
// This is NOT a mock of an unwired backend endpoint — HomeView.vue is a
// marketing/landing page with no backend contract at all (out of scope for
// the whole UI-0..UI-4 effort). It previously lived at
// src/api/mocks/homeMocks.ts, but Stage UI-0 deletes src/api/mocks/ entirely
// (that directory is the stale fake-data layer that silently masked broken
// admin views). This file is content colocated with the page that renders
// it, not a mock — moved here, unchanged, so HomeView.vue keeps compiling.
export interface HomePageFeature {
  iconName: string
  title: string
  description: string
  bgClass: string
  textClass: string
}

export interface HomePageStat {
  value: string
  label: string
}

export interface HomePageData {
  features: HomePageFeature[]
  stats: HomePageStat[]
  checklist: string[]
}

export const homePageMock: HomePageData = {
  features: [
    {
      iconName: 'ShieldCheck',
      title: 'OAuth2.1 & OpenID Connect',
      description: 'Built with modern authentication standards for maximum security and compatibility.',
      bgClass: 'bg-primary/10',
      textClass: 'text-primary',
    },
    {
      iconName: 'Users',
      title: 'Multi-Tenant Support',
      description: 'Manage multiple applications and organizations with per-tenant branding and configuration.',
      bgClass: 'bg-green-500/10',
      textClass: 'text-green-600',
    },
    {
      iconName: 'KeyRound',
      title: 'Hybrid Token Strategy',
      description: 'JWT access tokens for performance, opaque refresh tokens for security.',
      bgClass: 'bg-violet-500/10',
      textClass: 'text-violet-600',
    },
    {
      iconName: 'Server',
      title: 'Microservices Ready',
      description: 'Seamless integration across distributed systems and microservice architectures.',
      bgClass: 'bg-amber-500/10',
      textClass: 'text-amber-600',
    },
  ],
  stats: [
    { value: '99.99%', label: 'Uptime SLA' },
    { value: '<50ms', label: 'Token Validation' },
    { value: '10M+', label: 'Auth Requests/Day' },
    { value: '2,500+', label: 'Active Teams' },
  ],
  checklist: [
    'Centralized identity management',
    'Role-based access control',
    'Secure password reset flows',
    'Session management',
    'API key management',
    'Audit logging & monitoring',
  ],
}
