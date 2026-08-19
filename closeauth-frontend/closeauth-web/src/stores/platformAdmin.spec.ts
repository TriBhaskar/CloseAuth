import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlatformAdminSessionStore } from './platformAdmin'
import type { PlatformAdminSessionState } from '@/api/platformAdminSession'

// FE-3a (spec §6.3.1): the session-expiry re-authentication state machine —
// requestReauth/completeReauth/dismissReauth — tested directly, independent
// of the layout that renders it (PlatformAdminLayout.spec.ts covers the UI).

const ACTIVE_STATE: PlatformAdminSessionState = {
  kind: 'active',
  adminId: 'admin-1',
  email: 'staff@closeauth.test',
  roles: ['PLATFORM_ADMIN'],
  accessTokenExpiresAt: '2026-01-01T00:05:00Z',
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('usePlatformAdminSessionStore — needsReauth', () => {
  it('starts null (no prompt) by default', () => {
    const store = usePlatformAdminSessionStore()
    expect(store.needsReauth).toBeNull()
  })

  it('requestReauth sets the mode', () => {
    const store = usePlatformAdminSessionStore()
    store.requestReauth('proactive')
    expect(store.needsReauth).toBe('proactive')
  })

  it('"expired" always wins — a late proactive call never downgrades an already-expired prompt', () => {
    const store = usePlatformAdminSessionStore()
    store.requestReauth('expired')
    store.requestReauth('proactive')
    expect(store.needsReauth).toBe('expired')
  })

  it('a fresh "expired" call after "proactive" does upgrade it (real expiry always takes over)', () => {
    const store = usePlatformAdminSessionStore()
    store.requestReauth('proactive')
    store.requestReauth('expired')
    expect(store.needsReauth).toBe('expired')
  })

  it('completeReauth updates state and clears the prompt', () => {
    const store = usePlatformAdminSessionStore()
    store.requestReauth('expired')

    store.completeReauth(ACTIVE_STATE)

    expect(store.needsReauth).toBeNull()
    expect(store.state).toEqual(ACTIVE_STATE)
  })

  it('dismissReauth clears the prompt without touching state', () => {
    const store = usePlatformAdminSessionStore()
    store.state = ACTIVE_STATE
    store.requestReauth('proactive')

    store.dismissReauth()

    expect(store.needsReauth).toBeNull()
    expect(store.state).toEqual(ACTIVE_STATE)
  })

  it('signOut also clears any pending reauth prompt', async () => {
    const store = usePlatformAdminSessionStore()
    store.state = ACTIVE_STATE
    store.requestReauth('expired')

    // signOut() calls the real api/platformAdminSession.signOut(), which
    // needs a fetch — this store test only cares about the local state
    // reset, so a minimal, always-ok stub is enough here.
    globalThis.fetch = (() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) })) as unknown as typeof fetch

    await store.signOut()

    expect(store.needsReauth).toBeNull()
    expect(store.state).toEqual({ kind: 'anonymous' })
  })
})
