import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import TenantAdminAuthErrorView from './TenantAdminAuthErrorView.vue'

async function mountWithReason(reason: string, slug = 'ten_acme-inc') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/t/:slug/auth-error', component: TenantAdminAuthErrorView }],
  })
  await router.push(`/t/${slug}/auth-error?reason=${reason}`)
  await router.isReady()
  return mount(TenantAdminAuthErrorView, { global: { plugins: [router] } })
}

describe('TenantAdminAuthErrorView', () => {
  // FE-2a: the resolver (TenantResolverView.vue) reaches this reason when
  // its own POST /api/auth/authorize/start call is rate-limited.
  it('rate_limited shows retryable copy with a retry link (transient, unlike login_loop)', async () => {
    const wrapper = await mountWithReason('rate_limited')

    expect(wrapper.text()).toContain('Too many attempts')
    expect(wrapper.find('a[href="/t/ten_acme-inc/admin/login"]').exists()).toBe(true)
  })

  it('unknown_tenant (the resolver-reachable case) shows the existing unknown-tenant copy', async () => {
    const wrapper = await mountWithReason('unknown_tenant')

    expect(wrapper.text()).toContain('does not have an admin console configured')
  })

  it('an unrecognised reason falls back to a generic message rather than blank content', async () => {
    const wrapper = await mountWithReason('something_new')

    expect(wrapper.text()).toContain('Something went wrong while signing in.')
  })
})
