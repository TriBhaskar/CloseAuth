import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, type Pinia } from 'pinia'
import RegisterView from './RegisterView.vue'

// Stage UI-2b, Deliverable 4's required component tests (Vue Test Utils,
// mocked fetch — same discipline as UI-2a's LoginView.spec.ts): one test per
// outcome RegisterView branches on. Deliverable 4 documents six outcomes
// (ACTIVE / PENDING+verify / PENDING+approval / 409 / 403 / 400-with-field-
// errors); all six are covered here for completeness (the stage prompt's
// own Testing section says "five" while Deliverable 4 lists six — resolved
// conservatively by testing every documented branch rather than dropping one,
// see the stage report).
//
// Every test mounts through a real router carrying `?client_id=` in the
// query so the submitted form body can be asserted, and stubs `fetch` so
// `/branding` (TenantBrandingProvider, fired on mount) always succeeds
// independently of whatever `/register` mock a given test installs — the
// exact pattern LoginView.spec.ts established.

// FE-2c: registrationMode defaults to null (absent), matching "no gate
// opinion" — the 7 pre-existing tests below never set this and must keep
// passing unmodified (isClosedMode(null) is false, so the route-level gate
// never blocks them; they exercise the SEPARATE, unrelated `mode` field the
// /register POST response itself carries).
function brandingResponse(registrationMode: string | null = null) {
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({
        logoUrl: '',
        primaryColor: '#4F46E5',
        backgroundColor: '#FFFFFF',
        accentColor: '#22D3EE',
        companyName: '',
        tenantSlug: 'ten_acme-inc',
        registrationMode,
      }),
  })
}

async function createRegisterRouter(query = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/t/:slug/login', component: { template: '<div />' } },
      { path: '/t/:slug/register', component: RegisterView },
      { path: '/t/:slug/verify-email', component: { template: '<div />' } },
      { path: '/not-found', component: { template: '<div />' } },
    ],
  })
  await router.push(query ? `/t/ten_acme-inc/register?${query}` : '/t/ten_acme-inc/register')
  await router.isReady()
  return router
}

function stubFetch(
  registerHandler: (url: string, init?: RequestInit) => Promise<unknown>,
  registrationMode: string | null = null,
) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.startsWith('/branding')) return brandingResponse(registrationMode)
      if (url === '/register') return registerHandler(url, init)
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

let pinia: Pinia

beforeEach(() => {
  // Fresh Pinia per test — TenantBrandingProvider's useThemeStore() needs an
  // active instance to mount at all. Each test installs its own /register
  // handler via stubFetch.
  pinia = createPinia()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// FE-2c: password/confirm now live inside NewPasswordFields (bare mode,
// id-prefix="register"), which mints #register-new/#register-confirm — NOT
// the old #register-password/#register-confirm-password literal ids.
async function fillRequiredFields(wrapper: ReturnType<typeof mount>, email: string, password: string) {
  await wrapper.find('#register-email').setValue(email)
  await wrapper.find('#register-new').setValue(password)
  await wrapper.find('#register-confirm').setValue(password)
}

describe('RegisterView', () => {
  it('status=ACTIVE shows the immediate-activation success state with a link to /login', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ userId: 'u1', status: 'ACTIVE', mode: 'OPEN', emailVerificationSent: false }),
      }),
    )
    const router = await createRegisterRouter('client_id=client-open')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'user@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('ready to use')
    expect(wrapper.find('a[href="/t/ten_acme-inc/login"]').exists()).toBe(true)
  })

  it('status=PENDING, emailVerificationSent=true routes to VerifyEmailView carrying the email', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({ userId: 'u2', status: 'PENDING', mode: 'EMAIL_VERIFIED', emailVerificationSent: true }),
      }),
    )
    const router = await createRegisterRouter('client_id=client-verify')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'verify-me@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/verify-email')
    expect(router.currentRoute.value.query.email).toBe('verify-me@example.test')
    expect(router.currentRoute.value.query.client_id).toBe('client-verify')
  })

  it('status=PENDING, emailVerificationSent=false shows a static pending-approval message with no next step', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({ userId: 'u3', status: 'PENDING', mode: 'ADMIN_APPROVED', emailVerificationSent: false }),
      }),
    )
    const router = await createRegisterRouter('client_id=client-approve')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'approve-me@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('awaiting administrator approval')
    // No actionable next step — no link (AuthShell's own "skip to form"
    // anchor is unrelated chrome, not part of this assertion) or submit
    // button rendered in this state.
    expect(wrapper.find('a[href="/t/ten_acme-inc/login"]').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('409 shows a generic inline "already exists" message on the email field', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: false,
        status: 409,
        json: () => Promise.resolve({ code: 'user.email_conflict', title: 'Conflict' }),
      }),
    )
    const router = await createRegisterRouter('client_id=client-dup')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'dup@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('already exists')
    // Enumeration-safe: never explicitly says "in this tenant".
    expect(alert.text()).not.toContain('tenant')
    // Still on the form — no navigation away.
    expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/register')
  })

  it('403 (invite-only, missing/invalid invite) shows a clear message directing the user to their invite email', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: false,
        status: 403,
        json: () => Promise.resolve({ code: 'registration.invite_required', title: 'Forbidden' }),
      }),
    )
    const router = await createRegisterRouter('client_id=client-invite')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'needs-invite@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text().toLowerCase()).toContain('invit')
    expect(alert.text()).not.toContain('registration.invite_required')
  })

  it('400 with a validation errors map surfaces field-level errors, not a generic banner', async () => {
    stubFetch(() =>
      Promise.resolve({
        ok: false,
        status: 400,
        json: () =>
          Promise.resolve({
            code: 'validation.failed',
            errors: {
              email: 'must be a well-formed email address',
              password: 'size must be between 8 and 200',
            },
          }),
      }),
    )
    const router = await createRegisterRouter('client_id=client-validation')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    // Long enough to pass NewPasswordFields' own client-side min-length
    // check — this test is specifically about a SERVER-reported password
    // error, which must never even be attempted if the client-side check
    // already caught it (that's a different message, tested separately in
    // NewPasswordFields.spec.ts).
    await fillRequiredFields(wrapper, 'not-an-email', 'long-enough-but-server-rejects-it')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // Field-level: the email/password inputs each carry their own message,
    // not one generic top-of-form banner.
    const html = wrapper.html()
    expect(html).toContain('must be a well-formed email address')
    expect(html).toContain('size must be between 8 and 200')

    const emailField = wrapper.find('#register-email')
    expect(emailField.attributes('aria-invalid')).toBe('true')
    // The server-reported password error has nowhere to render inside
    // NewPasswordFields (it owns its own, client-only fieldErrors) — it
    // surfaces via the banner instead (see RegisterView.vue's own comment).
    // find() alone would hit the email field's OWN alert first (rendered
    // earlier in the template) — check across every alert on the page.
    const alerts = wrapper.findAll('[role="alert"]')
    expect(alerts.some((a) => a.text().includes('size must be between 8 and 200'))).toBe(true)
  })

  it('never renders the invite token as an editable form field, even on the invite-flow copy', async () => {
    stubFetch(() => Promise.reject(new Error('should not submit in this test')))
    const router = await createRegisterRouter('client_id=client-x&invite=super-secret-invite-token')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Accept your invite')
    expect(wrapper.html()).not.toContain('super-secret-invite-token')
    // Confirms no input anywhere carries the raw token as its value/model.
    const inputs = wrapper.findAll('input')
    for (const input of inputs) {
      expect((input.element as HTMLInputElement).value).not.toBe('super-secret-invite-token')
    }
  })

  // FE-2c: the route-level gate (spec §6.2.3) — resolves branding BEFORE
  // rendering, mirroring TenantResolverView.vue's pattern.
  describe('route-level gate', () => {
    it('ADMIN_APPROVED always redirects to /not-found, without ever rendering the form', async () => {
      stubFetch(() => Promise.reject(new Error('should not submit in this test')), 'ADMIN_APPROVED')
      const router = await createRegisterRouter('client_id=client-closed')
      const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/not-found')
      expect(wrapper.find('#register-email').exists()).toBe(false)
    })

    it('INVITE_ONLY without ?invite= redirects to /not-found', async () => {
      stubFetch(() => Promise.reject(new Error('should not submit in this test')), 'INVITE_ONLY')
      const router = await createRegisterRouter('client_id=client-closed')
      const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/not-found')
      expect(wrapper.find('#register-email').exists()).toBe(false)
    })

    it('INVITE_ONLY with ?invite= renders the form, not a 404 — a bad/reused token is still a 403 at submit', async () => {
      stubFetch(() => Promise.reject(new Error('should not submit in this test')), 'INVITE_ONLY')
      const router = await createRegisterRouter('client_id=client-invited&invite=some-token')
      const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/register')
      expect(wrapper.find('#register-email').exists()).toBe(true)
    })

    it('OPEN and EMAIL_VERIFIED never block the route', async () => {
      for (const mode of ['OPEN', 'EMAIL_VERIFIED']) {
        stubFetch(() => Promise.reject(new Error('should not submit in this test')), mode)
        const router = await createRegisterRouter('client_id=client-open')
        const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
        await flushPromises()

        expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/register')
        expect(wrapper.find('#register-email').exists()).toBe(true)
      }
    })

    it('a branding-fetch failure fails open — the form still renders rather than 404ing', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn((url: string) => {
          if (url.startsWith('/branding')) return Promise.reject(new Error('network down'))
          return Promise.reject(new Error(`unexpected fetch: ${url}`))
        }),
      )
      const router = await createRegisterRouter('client_id=client-flaky')
      const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/t/ten_acme-inc/register')
      expect(wrapper.find('#register-email').exists()).toBe(true)
    })
  })

  // FE-2c: invite email pre-fill + lock (spec §6.2.3).
  it('an invite link carrying &email= pre-fills and locks the email field', async () => {
    stubFetch(() => Promise.reject(new Error('should not submit in this test')), 'INVITE_ONLY')
    const router = await createRegisterRouter('client_id=client-invited&invite=some-token&email=invited%40example.test')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    const emailField = wrapper.find('#register-email')
    expect((emailField.element as HTMLInputElement).value).toBe('invited@example.test')
    expect(emailField.attributes('readonly')).toBeDefined()
  })

  it('an invite link WITHOUT &email= leaves the email field editable', async () => {
    stubFetch(() => Promise.reject(new Error('should not submit in this test')), 'INVITE_ONLY')
    const router = await createRegisterRouter('client_id=client-invited&invite=some-token')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    const emailField = wrapper.find('#register-email')
    expect(emailField.attributes('readonly')).toBeUndefined()
  })

  // FE-2c: the live password checklist is wired through in bare mode —
  // NewPasswordFields.spec.ts covers its own behavior in isolation; this is
  // the thin integration proof that RegisterView actually turns it on.
  it('shows the live password checklist and updates it reactively', async () => {
    stubFetch(() => Promise.reject(new Error('should not submit in this test')))
    const router = await createRegisterRouter('client_id=client-checklist')
    const wrapper = mount(RegisterView, { global: { plugins: [router, pinia] } })
    await flushPromises()

    const items = wrapper.findAll('li')
    expect(items.length).toBeGreaterThanOrEqual(2)
    expect(wrapper.text()).toContain('At least 8 characters')
    expect(wrapper.text()).toContain('Passwords match')

    await wrapper.find('#register-new').setValue('Correct-Pw-123!')
    await wrapper.find('#register-confirm').setValue('Correct-Pw-123!')

    const updated = wrapper.findAll('li')
    expect(updated.some((li) => li.text().includes('✓'))).toBe(true)
  })
})
