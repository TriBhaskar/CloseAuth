// ── Centralised API Client ────────────────────────────────────────────────────
// Reads VITE_API_BASE_URL from the environment, defaulting to '/api'.
// All service files should import `apiClient` from here — never use raw fetch().

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

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

  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...(init.headers ?? {}),
  }

  let response: Response
  try {
    response = await fetch(url, { ...init, headers })
  } catch (networkErr) {
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

