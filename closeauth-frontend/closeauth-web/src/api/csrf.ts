// FE-1.11: the CSRF token cache, split out of the now-deleted api/client.ts
// (that file's own request()/apiClient/ApiError had zero real call sites —
// every console transport bypasses it for tenant/platform-specific 401
// handling, see tenantAdminClient.ts's header comment). This is the one
// piece of it that IS still live: the CSRF cookie/token is Path=/ and
// surface-agnostic (one BFF-wide token), shared by tenantAdminFetch and
// platformAdminFetch rather than each re-fetching its own.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

let csrfToken: string | null = null

// Clear the cached CSRF token (call on logout so the next POST re-fetches)
export function clearCsrfToken(): void {
  csrfToken = null
}

export async function fetchCsrfToken(): Promise<string | null> {
  try {
    const resp = await fetch(`${BASE_URL}/csrf`, { credentials: 'include' })
    if (resp.ok) {
      const data = await resp.json()
      csrfToken = data.token ?? null
    }
  } catch {
    // Silently fail — CSRF will be retried on next request
  }

  return csrfToken
}

export function getCsrfToken(): string | null {
  return csrfToken
}
