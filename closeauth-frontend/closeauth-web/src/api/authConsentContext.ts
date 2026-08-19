// FE-1.11: the consent-CONTEXT fetch, moved out of ConsentView.vue so no
// component-level fetch survives (spec §9 rule 2). The consent DECISION
// itself stays a native <form method="post"> straight to the backend's own
// origin in ConsentView.vue — see that file's own load-bearing header
// comment for why that must never become a fetch()/XHR call. This module is
// only the context GET (handlers_consent_proxy.go's /oauth2/consent proxy),
// unauthenticated, credentials:'omit', root-path — like publicBranding.ts,
// it doesn't fit tenantAdminFetch/platformAdminFetch's console-session
// shape, so it's a small standalone module. Never throws — the house
// discriminated-union convention every src/api/* module here follows.
export interface ConsentScope {
  scope: string
  description: string
  requiresConsent: boolean
}

export interface ConsentContext {
  clientId: string
  clientName: string
  state: string
  scopes: ConsentScope[]
  alreadyGranted: string[]
  authorizeUrl: string
}

export type ConsentContextResult = { kind: 'ok'; value: ConsentContext } | { kind: 'error' }

/** `search` is the captured `window.location.search` — client_id/scope/state, appended by SAS onto the consent-page redirect, forwarded verbatim. */
export async function fetchConsentContext(search: string): Promise<ConsentContextResult> {
  try {
    const response = await fetch(`/oauth2/consent${search}`, { credentials: 'omit' })
    if (!response.ok) return { kind: 'error' }
    return { kind: 'ok', value: (await response.json()) as ConsentContext }
  } catch {
    return { kind: 'error' }
  }
}
