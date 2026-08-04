// Stage UI-2c-i, Deliverable 4's nice-to-have: a lightweight, best-effort
// carry-through of the original /oauth2/authorize query string across the
// password-reset email round trip, so ResetPasswordView's post-success
// "log in" link can restore OAuth context (client_id, redirect_uri, state,
// ...) if the reset link happens to be opened in the SAME browser session
// that requested it.
//
// sessionStorage (not localStorage, not a server-side mechanism) is
// deliberate: this is a courtesy for the common same-tab/browser case, not
// a durable, cross-device delivery mechanism. Losing it — a different tab
// or browser than the one that clicked "Forgot password?", a cleared
// session, a private window, storage disabled — is a NORMAL, expected
// outcome of an email-based flow (per the stage prompt's explicit
// call-out), not a bug to engineer around. The fallback is always a bare
// `/login` with no client_id, which is always safe.
const STORAGE_KEY = 'closeauth.forgotPassword.authorizeQuery'

/** Called when "Forgot password?" is clicked on LoginView.vue. */
export function saveForgotPasswordQuery(query: string): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, query)
  } catch {
    // Storage can throw (private browsing, disabled storage, quota) —
    // losing this nice-to-have silently is fine; nothing downstream depends
    // on it succeeding.
  }
}

/** Called by ResetPasswordView.vue when constructing its post-success "log in" link. */
export function readForgotPasswordQuery(): string {
  try {
    return sessionStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}
