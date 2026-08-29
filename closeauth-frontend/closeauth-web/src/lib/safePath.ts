// FE-6.6: the one shared check for "is this navigation target actually
// same-origin" — used everywhere this app navigates off a value it didn't
// construct itself (a `returnTo` query param, a `reauthPath` from a 401
// response body). A single leading `/` and never `//` (which the browser
// resolves as protocol-relative, i.e. an implicit cross-origin target) —
// the same root-relative shape app/guards.ts already produces for every
// `returnTo` it sets itself.
export function isSameOriginPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//')
}
