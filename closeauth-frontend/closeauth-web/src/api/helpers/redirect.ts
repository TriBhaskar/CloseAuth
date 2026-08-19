import router from '@/app/router'

/**
 * Handles redirect URLs returned by the Go API.
 *
 * Strategy:
 * - Starts with `/closeauth/` or `http` → globalThis.location.href (browser navigation, Go handles)
 * - Starts with `/` → router.push() (SPA internal navigation)
 *
 * Kept as-is in Stage UI-0: this file has no dependency on the deleted
 * api/models, api/services, or api/mocks, and the internal/vs-external
 * redirect-routing pattern is generic. The hardcoded `/closeauth/` prefix
 * still matches the surviving Go-side convention (internal/static's
 * SPAHandler skips `/api/` and `/closeauth/`). TODO(ui-1): re-verify this
 * prefix assumption once the rebuilt routes.go finalizes its route tree.
 */
export function handleRedirect(redirectUrl: string): void {
  if (!redirectUrl) return

  if (redirectUrl.indexOf('http') === 0 || redirectUrl.indexOf('/closeauth/') === 0) {
    // External URL or OAuth proxy route — full browser navigation
    globalThis.location.href = redirectUrl
  } else if (redirectUrl.indexOf('/') === 0) {
    // Internal SPA route
    router.push(redirectUrl)
  }
}

/**
 * Checks if an API response contains a redirect_url and handles it.
 * Returns true if a redirect was triggered, false otherwise.
 */
export function checkAndHandleRedirect(data: unknown): boolean {
  if (data && typeof data === 'object' && 'redirect_url' in data) {
    const url = (data as { redirect_url: string }).redirect_url
    if (url) {
      handleRedirect(url)
      return true
    }
  }
  return false
}


