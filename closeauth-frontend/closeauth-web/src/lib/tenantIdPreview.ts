// FE-3b (spec §1.2, §6.3.2): the provision dialog's *provisional* Tenant ID
// preview — "Tenant ID will be ten_acme-inc · can't be changed later",
// updating live as the operator types the tenant name. This is a
// client-side port of tenant/service/TenantSlugGenerator.java's
// DETERMINISTIC steps only (lowercase + NFKD-normalise + strip diacritics,
// collapse non-alphanumeric runs to a hyphen, trim, truncate at a hyphen
// boundary to 40 chars) — never the random collision-suffix or
// empty-body-fallback steps, since those need live DB state / randomness
// this preview can't honestly reproduce. When the deterministic body would
// be empty or land on the reserved list, previewTenantId returns null
// rather than fabricating a value the real, server-generated ID won't
// match — the caller falls back to a generic "will be generated" line.
const PREFIX = 'ten_'
const MAX_BODY_LENGTH = 40

// Mirrors TenantSlugGenerator.java's RESERVED set exactly.
const RESERVED = new Set([
  'platform', 'admin', 'api', 'auth', 'oauth2', 'login', 'logout',
  'console', 'account', 't', 'www', 'static', 'health', 'closeauth',
])

const NON_ALNUM_RUN = /[^a-z0-9]+/g
const LEADING_TRAILING_HYPHENS = /(^-+)|(-+$)/g
// JS's Unicode property escapes require the 'u' flag; \p{M} matches
// combining marks, the same class Java's COMBINING_MARKS pattern targets.
const COMBINING_MARKS = /\p{M}+/gu

function slugify(name: string): string {
  const decomposed = name.normalize('NFKD')
  const stripped = decomposed.replace(COMBINING_MARKS, '')
  const lower = stripped.toLowerCase()
  const hyphenated = lower.replace(NON_ALNUM_RUN, '-')
  const trimmed = hyphenated.replace(LEADING_TRAILING_HYPHENS, '')
  return truncateAtHyphenBoundary(trimmed, MAX_BODY_LENGTH)
}

function truncateAtHyphenBoundary(body: string, maxLength: number): string {
  if (body.length <= maxLength) return body
  const cut = body.slice(0, maxLength)
  const lastHyphen = cut.lastIndexOf('-')
  return lastHyphen > 0 ? cut.slice(0, lastHyphen) : cut
}

function isReserved(body: string): boolean {
  return body.length === 1 || RESERVED.has(body)
}

/**
 * Returns the provisional `ten_...` preview for `name`, or `null` when no
 * honest deterministic preview exists (empty body, or a reserved word that
 * the real backend would suffix with a random collision tag).
 */
export function previewTenantId(name: string): string | null {
  const body = slugify(name)
  if (body === '' || isReserved(body)) return null
  return PREFIX + body
}
