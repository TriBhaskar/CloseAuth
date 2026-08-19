// FE-1.11: both tenantAdminFetch and platformAdminFetch synthesize a
// Response on a caught network exception (backend/BFF unreachable) rather
// than throwing, per the house discriminated-union convention. Previously
// each did `new Response(null, { status: 503 })` inline — indistinguishable
// from a genuine upstream 503 the backend itself returned. §7.3 wants
// different copy for "can't reach CloseAuth" vs "CloseAuth is broken"; a
// sentinel header on the synthetic response is what problem.ts's category
// mapper reads to tell them apart.
const UNREACHABLE_HEADER = 'X-CloseAuth-Unreachable'

export function unreachableResponse(): Response {
  return new Response(null, { status: 503, headers: { [UNREACHABLE_HEADER]: '1' } })
}

export function isUnreachable(response: Response): boolean {
  // Optional chaining is defensive, not decorative: many existing specs
  // stub global fetch with a plain `{ ok, status, json }` object rather than
  // a real Response (headers/clone() etc. omitted) — a real fetch() always
  // returns a Response with .headers, so this only ever matters in tests.
  return response.headers?.has(UNREACHABLE_HEADER) ?? false
}
