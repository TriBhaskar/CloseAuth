// ── Centralised API Client ────────────────────────────────────────────────────
// Reads VITE_API_BASE_URL from the environment, defaulting to '/api'.
// All service files should import `apiClient` from here — never use raw fetch().

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

// ── CSRF Token Manager ────────────────────────────────────────────────────────

let csrfToken: string | null = null
let csrfTokenPromise: Promise<string | null> | null = null

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

async function ensureCsrfToken(): Promise<void> {
  if (csrfToken) return

  csrfTokenPromise ??= fetchCsrfToken().finally(() => {
    csrfTokenPromise = null
  })

  await csrfTokenPromise
}

// ── Typed error ───────────────────────────────────────────────────────────────

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

// ── Core request helper ───────────────────────────────────────────────────────

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const url = `${BASE_URL}${path}`

  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')

  // Inject CSRF token on mutating requests
  const method = (init.method ?? 'GET').toUpperCase()
  if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
    await ensureCsrfToken()

    if (csrfToken) {
      headers.set('X-CSRF-Token', csrfToken)
    }
  }

  let response: Response
  try {
    response = await fetch(url, { ...init, headers, credentials: 'include' })
  } catch {
    // Backend is down / ECONNREFUSED — treat as 503
    throw new ApiError(503, 'Backend unavailable')
  }

  // Try to parse JSON regardless of status so we can surface server messages
  const json = await response.json().catch(() => null)

  if (!response.ok) {
    const message: string =
      (json as { error?: string })?.error ??
      `Request failed with status ${response.status}`
    throw new ApiError(response.status, message)
  }

  return json as T
}

// ── Exported helpers ──────────────────────────────────────────────────────────

export const apiClient = {
  get<T>(path: string, init?: RequestInit): Promise<T> {
    return request<T>(path, { ...init, method: 'GET' })
  },

  post<T>(path: string, body: unknown, init?: RequestInit): Promise<T> {
    return request<T>(path, { ...init, method: 'POST', body: JSON.stringify(body) })
  },

  put<T>(path: string, body: unknown, init?: RequestInit): Promise<T> {
    return request<T>(path, { ...init, method: 'PUT', body: JSON.stringify(body) })
  },

  delete<T>(path: string, init?: RequestInit): Promise<T> {
    return request<T>(path, { ...init, method: 'DELETE' })
  },
}

