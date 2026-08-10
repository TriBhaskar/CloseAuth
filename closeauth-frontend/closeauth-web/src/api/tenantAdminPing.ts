// Stage UI-3a: GET /t/{slug}/api/ping — the landing page's actual proof that
// the loop works end to end (a real backend @RequiresTenantAccess-gated
// endpoint accepting this session's token). See
// internal/server/handlers_admin_ping.go.
import { tenantAdminFetch } from '@/api/tenantAdminClient'

export type TenantAdminPingResult =
  | { kind: 'ok'; tenantId: string }
  | { kind: 'reauth' }
  | { kind: 'error'; status: number; message: string }

interface PingBody {
  ok?: boolean
  tenantId?: string
  error?: string
  error_description?: string
}

export async function fetchPing(slug: string): Promise<TenantAdminPingResult> {
  const result = await tenantAdminFetch(slug, '/ping')
  if (result.kind === 'reauth') return { kind: 'reauth' }

  const { response } = result
  const data = (await response.json().catch(() => null)) as PingBody | null

  if (response.ok && data?.ok) {
    return { kind: 'ok', tenantId: data.tenantId ?? '' }
  }

  return {
    kind: 'error',
    status: response.status,
    message: data?.error_description ?? data?.error ?? `Request failed with status ${response.status}`,
  }
}
