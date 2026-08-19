import { describe, it, expect, vi, afterEach } from 'vitest'
import { useTenantOnboarding } from './useTenantOnboarding'

// FE-3b: extracted from PlatformTenantsView.vue (Stage UI-4b) once the
// tenant detail page became a genuine second consumer. Two behavioural
// changes proven here: the provision chain's auto-activate (+ its
// activateFailed recovery state, replacing the old 3-way activatePrompt)
// and the success hand-off becoming a SecretRevealPanel-shaped
// successPanel/successPanelFields, never open at the same time as
// onboardingState (see the module's own header comment on the nested-dialog
// risk this avoids).

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

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useTenantOnboarding — auto-activate chain', () => {
  it('activateAndOpenBootstrap: success re-fetches and opens the bootstrap form directly (no intermediate dialog)', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/activate' && init?.method === 'POST') {
        return { ok: true, status: 200, body: { id: 'tenant-1', slug: 'ten_acme-inc', status: 'ACTIVE' } }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { onboardingState, activateAndOpenBootstrap } = useTenantOnboarding(onSuccess)

    await activateAndOpenBootstrap('tenant-1', 'ten_acme-inc', 'Acme Inc')

    expect(onSuccess).toHaveBeenCalledTimes(1)
    expect(onboardingState.value).toEqual({ kind: 'bootstrapForm', tenantId: 'tenant-1', slug: 'ten_acme-inc' })
  })

  it('activateAndOpenBootstrap: failure shows the activateFailed recovery state, never a raw error', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/activate' && init?.method === 'POST') {
        return { ok: false, status: 500, body: { error: 'server_error', error_description: 'boom' } }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { onboardingState, activateAndOpenBootstrap } = useTenantOnboarding(onSuccess)

    await activateAndOpenBootstrap('tenant-1', 'ten_acme-inc', 'Acme Inc')

    expect(onboardingState.value?.kind).toBe('activateFailed')
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it('retryActivate re-attempts activation for the tenant in the activateFailed state', async () => {
    let attempt = 0
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/activate' && init?.method === 'POST') {
        attempt += 1
        return attempt === 1
          ? { ok: false, status: 500, body: { error: 'server_error', error_description: 'boom' } }
          : { ok: true, status: 200, body: { id: 'tenant-1', slug: 'ten_acme-inc', status: 'ACTIVE' } }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { onboardingState, activateAndOpenBootstrap, retryActivate } = useTenantOnboarding(onSuccess)
    await activateAndOpenBootstrap('tenant-1', 'ten_acme-inc', 'Acme Inc')
    expect(onboardingState.value?.kind).toBe('activateFailed')

    retryActivate()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(onboardingState.value).toEqual({ kind: 'bootstrapForm', tenantId: 'tenant-1', slug: 'ten_acme-inc' })
  })
})

describe('useTenantOnboarding — bootstrap form', () => {
  it('a mismatched confirm-email blocks submission without a network call', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const onSuccess = vi.fn()
    const { openBootstrapForm, bootstrapForm, bootstrapErrors, handleBootstrap } = useTenantOnboarding(onSuccess)
    openBootstrapForm('tenant-1', 'ten_acme-inc')
    bootstrapForm.email = 'ada@acme.test'
    bootstrapForm.confirmEmail = 'ada+typo@acme.test'

    await handleBootstrap()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(bootstrapErrors.confirmEmail).toContain('does not match')
  })

  it('success closes the bootstrap dialog and opens the success panel with all three hand-off fields', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/bootstrap-admin' && init?.method === 'POST') {
        return {
          ok: true,
          status: 201,
          body: {
            user: { id: 'u-1', email: 'ada@acme.test' },
            temporaryPassword: 'Xk4$superSecret',
            temporaryPasswordExpiresAt: '2026-01-08T00:00:00Z',
          },
        }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { openBootstrapForm, bootstrapForm, handleBootstrap, onboardingState, successPanel, successPanelFields } =
      useTenantOnboarding(onSuccess)
    openBootstrapForm('tenant-1', 'ten_acme-inc')
    bootstrapForm.email = 'ada@acme.test'
    bootstrapForm.confirmEmail = 'ada@acme.test'

    await handleBootstrap()

    expect(onboardingState.value).toBeNull()
    expect(successPanel.value?.email).toBe('ada@acme.test')
    expect(onSuccess).toHaveBeenCalledTimes(1)

    const fields = successPanelFields.value
    expect(fields.find((f) => f.id === 'tenant-id')?.value).toBe('ten_acme-inc')
    expect(fields.find((f) => f.id === 'signin-url')?.value).toContain('/t/ten_acme-inc/login')
    expect(fields.find((f) => f.id === 'temp-password')).toMatchObject({ value: 'Xk4$superSecret', maskable: true })
  })

  it('a 403 tenant.not_active mid-bootstrap shows spec-literal recovery copy, form stays resubmittable', async () => {
    stubFetch((url, init) => {
      if (url === '/platform/api/tenants/tenant-1/bootstrap-admin' && init?.method === 'POST') {
        return { ok: false, status: 403, body: { error: 'tenant.not_active', error_description: 'not active' } }
      }
      return null
    })
    const onSuccess = vi.fn()
    const { openBootstrapForm, bootstrapForm, handleBootstrap, onboardingState, bootstrapBanner } = useTenantOnboarding(onSuccess)
    openBootstrapForm('tenant-1', 'ten_acme-inc')
    bootstrapForm.email = 'ada@acme.test'
    bootstrapForm.confirmEmail = 'ada@acme.test'

    await handleBootstrap()

    expect(bootstrapBanner.value).toBe("Tenant created, but the admin couldn't be added yet. Retry.")
    expect(onboardingState.value?.kind).toBe('bootstrapForm') // still open, not abandoned
  })
})

describe('useTenantOnboarding — onboardingAction classification', () => {
  it('reuses the exact status+adminCount rules from before extraction', () => {
    const { onboardingAction } = useTenantOnboarding(vi.fn())
    const base = { id: 't', slug: 'ten_t', name: 'T', createdAt: '', updatedAt: '', deletedAt: null } as const

    expect(onboardingAction({ ...base, status: 'ACTIVE', adminCount: 0 })).toBe('bootstrap')
    expect(onboardingAction({ ...base, status: 'ACTIVE', adminCount: 2 })).toBe('reissue')
    expect(onboardingAction({ ...base, status: 'ACTIVE', adminCount: null })).toBeNull()
    expect(onboardingAction({ ...base, status: 'PROVISIONING', adminCount: 0 })).toBeNull()
  })
})
