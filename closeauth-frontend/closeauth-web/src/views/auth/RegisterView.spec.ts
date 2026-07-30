import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
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
// `/branding` (useOAuthTheme, fired on mount) always succeeds independently
// of whatever `/register` mock a given test installs — the exact pattern
// LoginView.spec.ts established.

function brandingResponse() {
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
      }),
  })
}

async function createRegisterRouter(query = '') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/register', component: RegisterView },
      { path: '/verify-email', component: { template: '<div />' } },
    ],
  })
  await router.push(query ? `/register?${query}` : '/register')
  await router.isReady()
  return router
}

function stubFetch(registerHandler: (url: string, init?: RequestInit) => Promise<unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.startsWith('/branding')) return brandingResponse()
      if (url === '/register') return registerHandler(url, init)
      return Promise.reject(new Error(`unexpected fetch: ${url}`))
    }),
  )
}

beforeEach(() => {
  // Placeholder — each test installs its own /register handler via stubFetch.
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function fillRequiredFields(wrapper: ReturnType<typeof mount>, email: string, password: string) {
  await wrapper.find('#register-email').setValue(email)
  await wrapper.find('#register-password').setValue(password)
  await wrapper.find('#register-confirm-password').setValue(password)
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
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'user@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('ready to use')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
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
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'verify-me@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/verify-email')
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
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'approve-me@example.test', 'Correct-Pw-123!')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.text()).toContain('awaiting administrator approval')
    // No actionable next step — no link (AuthLayout's own "skip to form"
    // anchor is unrelated chrome, not part of this assertion) or submit
    // button rendered in this state.
    expect(wrapper.find('a[href="/login"]').exists()).toBe(false)
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
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
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
    expect(router.currentRoute.value.path).toBe('/register')
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
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
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
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
    await flushPromises()

    await fillRequiredFields(wrapper, 'not-an-email', 'short')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    // Field-level: the email/password inputs each carry their own message,
    // not one generic top-of-form banner.
    const html = wrapper.html()
    expect(html).toContain('must be a well-formed email address')
    expect(html).toContain('size must be between 8 and 200')

    const emailField = wrapper.find('#register-email')
    expect(emailField.attributes('aria-invalid')).toBe('true')
    const passwordField = wrapper.find('#register-password')
    expect(passwordField.attributes('aria-invalid')).toBe('true')
  })

  it('never renders the invite token as an editable form field, even on the invite-flow copy', async () => {
    stubFetch(() => Promise.reject(new Error('should not submit in this test')))
    const router = await createRegisterRouter('client_id=client-x&invite=super-secret-invite-token')
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Accept your invite')
    expect(wrapper.html()).not.toContain('super-secret-invite-token')
    // Confirms no input anywhere carries the raw token as its value/model.
    const inputs = wrapper.findAll('input')
    for (const input of inputs) {
      expect((input.element as HTMLInputElement).value).not.toBe('super-secret-invite-token')
    }
  })
})
