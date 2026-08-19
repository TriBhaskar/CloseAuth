// FE-2a (spec §1.2, §6.1): the entry field's "forgiving on input, strict on
// display" normalisation. Mirrors internal/server/handlers_entry_proxy.go's
// normalizeTenantID rule for rule — the BFF re-normalises independently
// rather than trusting this (spec §6.1's Data line), so the two must agree,
// not just one defer to the other.
const TENANT_ID_PATTERN = /^ten_[a-z0-9][a-z0-9-]{1,45}$/

export function normalizeTenantId(raw: string): string {
  let value = raw.trim().toLowerCase()

  const marker = '/t/'
  const idx = value.indexOf(marker)
  if (idx !== -1) {
    let rest = value.slice(idx + marker.length)
    const cut = rest.search(/[/?#]/)
    if (cut !== -1) rest = rest.slice(0, cut)
    value = rest
  }

  if (value && !value.startsWith('ten_')) {
    value = `ten_${value}`
  }

  return value
}

export function isValidTenantId(value: string): boolean {
  return TENANT_ID_PATTERN.test(value)
}
