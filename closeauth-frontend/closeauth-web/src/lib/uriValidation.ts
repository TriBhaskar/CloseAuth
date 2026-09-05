// Extracted from CreateClientDialog.vue (client update/delete stage) so the
// new client edit form can share the exact same redirect-URI rule rather
// than drifting a second copy of it. Spec's own rule (absolute URI, no
// fragment, https unless the host is localhost/127.0.0.1), applied
// uniformly across every client type including Native/mobile — no
// custom-scheme exception; see CreateClientDialog.vue's file header for why.
export function validateUri(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    return 'Must be an absolute URI.'
  }
  if (parsed.hash) return 'Must not include a fragment.'
  const isLocal = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1'
  if (parsed.protocol !== 'https:' && !isLocal) return 'Must use https, unless the host is localhost.'
  return null
}

/** Validates every entry in a URI list, returning only the indices with an error. */
export function validateUriList(uris: string[]): Record<number, string> {
  const errors: Record<number, string> = {}
  uris.forEach((uri, index) => {
    const error = validateUri(uri)
    if (error) errors[index] = error
  })
  return errors
}
