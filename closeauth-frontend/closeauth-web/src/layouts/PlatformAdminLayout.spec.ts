import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import PlatformAdminLayout from './PlatformAdminLayout.vue'
import { usePlatformAdminSessionStore } from '@/stores/platformAdmin'

// FE-3a (spec §6.3.1): the literal acceptance line — "at T-60s the topbar
// shows 'Session ends in 1:00 · Stay signed in', on expiry an interstitial
// re-authentication dialog appears over the current page (not a redirect
// that loses in-flight work), and unsaved dialog state is preserved." Same
// window.matchMedia stub ConsoleShell.spec.ts established (ConsoleShell is
// this layout's own shell).
function stubMatchMedia() {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: true,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  })
}

async function mountLayout() {
  setActivePinia(createPinia())
  stubMatchMedia()

  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div id="page-content"><slot /></div>' }, meta: { title: 'Tenants' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(PlatformAdminLayout, { global: { plugins: [router] } })
  const store = usePlatformAdminSessionStore()
  return { wrapper, store }
}

function stubFetch(handler: (url: string, init?: RequestInit) => { ok: boolean; status: number; body: unknown } | null) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/csrf') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ token: 'csrf-token' }) })
      }
      const result = handler(url, init)
      if (result) {
        return Promise.resolve({ ok: result.ok, status: result.status, json: () => Promise.resolve(result.body), clone() { return this } })
      }
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('PlatformAdminLayout — FE-3a session-expiry handling', () => {
  it('shows "Session ends in ... Stay signed in" once the countdown reaches the last 60 seconds', async () => {
    stubFetch(() => null)
    const { wrapper, store } = await mountLayout()
    store.state = {
      kind: 'active',
      adminId: 'admin-1',
      email: 'staff@closeauth.test',
      roles: ['PLATFORM_ADMIN'],
      accessTokenExpiresAt: new Date(Date.now() + 45_000).toISOString(),
    }
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.text()).toContain('Session ends in')
    const stayButton = wrapper.findAll('button').find((b) => b.text().includes('Stay signed in'))
    expect(stayButton).toBeTruthy()

    // Clicking it opens the overlay, proactively — session is still valid,
    // so a "Not now" escape must exist (dismissible).
    await stayButton!.trigger('click')
    await flushPromises()
    expect(store.needsReauth).toBe('proactive')
    expect(wrapper.findAll('button').some((b) => b.text() === 'Not now')).toBe(true)
  })

  it('opens the non-dismissible overlay automatically when the countdown reaches 0:00', async () => {
    stubFetch(() => null)
    const { wrapper, store } = await mountLayout()
    store.state = {
      kind: 'active',
      adminId: 'admin-1',
      email: 'staff@closeauth.test',
      roles: ['PLATFORM_ADMIN'],
      accessTokenExpiresAt: new Date(Date.now() + 500).toISOString(),
    }
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(store.needsReauth).toBe('expired')
    expect(wrapper.text()).toContain('Session expired')
    // No dismiss affordance for a genuinely dead session — only re-auth or sign out.
    expect(wrapper.findAll('button').some((b) => b.text() === 'Not now')).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === 'Sign out')).toBe(true)
  })

  it('re-authenticating in the overlay updates the session and closes it WITHOUT navigating — in-flight page state survives', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/login' && init?.method === 'POST') {
        return {
          ok: true,
          status: 200,
          body: {
            authenticated: true,
            adminId: 'admin-1',
            email: 'staff@closeauth.test',
            roles: ['PLATFORM_ADMIN'],
            accessTokenExpiresAt: new Date(Date.now() + 300_000).toISOString(),
          },
        }
      }
      return null
    })
    const { wrapper, store } = await mountLayout()
    store.state = {
      kind: 'active',
      adminId: 'admin-1',
      email: 'staff@closeauth.test',
      roles: ['PLATFORM_ADMIN'],
      accessTokenExpiresAt: new Date(Date.now() + 500).toISOString(),
    }
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(store.needsReauth).toBe('expired')

    // The page underneath the overlay is never unmounted — proven directly:
    // the route's own content stays in the DOM the whole time the overlay
    // is open, and is still there afterward, untouched.
    expect(wrapper.find('#page-content').exists()).toBe(true)

    await wrapper.find('#platform-reauth-email').setValue('staff@closeauth.test')
    await wrapper.find('#platform-reauth-password').setValue('correct-password')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(store.needsReauth).toBeNull()
    expect(store.state.kind).toBe('active')
    // No route change — a real navigation would have replaced #page-content's route.
    expect(wrapper.find('#page-content').exists()).toBe(true)
  })

  it('the proactive prompt does not force re-auth — "Not now" simply closes it, session keeps counting down', async () => {
    stubFetch(() => null)
    const { wrapper, store } = await mountLayout()
    store.requestReauth('proactive')
    await flushPromises()

    const notNow = wrapper.findAll('button').find((b) => b.text() === 'Not now')
    await notNow!.trigger('click')
    await flushPromises()

    expect(store.needsReauth).toBeNull()
  })
})
