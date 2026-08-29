// Stage UI-3e: the read-only audit query API layer, talking to
// internal/server/handlers_admin_audit.go's /t/{slug}/api/audit-events GET
// route. No mutations — this module has no POST/PUT/DELETE at all.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'
import type { PageView } from '@/api/tenantAdminUsers'

// Mirrors audit/dto/AuditEventView.java exactly. The DTO is
// @JsonInclude(NON_NULL) — a null field is an ABSENT KEY on the wire, not a
// JSON null, so every nullable field here is optional (`?:`), never `| null`.
// id/eventType/outcome/createdAt are the only fields guaranteed present.
// There is no `occurredAt` field — `createdAt` is the only timestamp.
export interface AuditEventView {
  id: string
  eventType: string
  outcome: 'SUCCESS' | 'FAILURE' | 'ERROR'
  tenantId?: string
  subjectUserId?: string
  actorUserId?: string
  actorPlatformAdminId?: string
  actorClientRegisteredId?: string
  resourceServerId?: string
  ipAddress?: string
  userAgent?: string
  errorCode?: string
  // @JsonRawValue on the backend — an inline JSON object, shape unspecified
  // by contract (every event type shapes its own payload). Never assume a
  // fixed structure; render it generically (see TenantAuditView.vue).
  eventData: unknown
  createdAt: string
}

export interface AuditFilters {
  eventType?: string
  from?: string
  to?: string
  userId?: string
  clientId?: string
  actor?: string
}

export const DEFAULT_AUDIT_PAGE_SIZE = 20

// FE-5.1 (spec §6.4.6): the filter bar's date-range presets. 'custom' is the
// only preset with no fixed lookback — the caller supplies from/to itself
// (the pre-existing datetime-local pair). AuditQueryParams.instant("to", …)
// is exclusive, but a preset's "now" is a moving target, not a value worth
// pinning into a shareable filter: a preset resolves ONLY a `from` cutoff
// and leaves `to` open, so revisiting a "last 1h" link later means "the
// hour before whenever this loads," which is what the preset actually means
// to an admin — not a frozen historical window.
export type AuditRangePreset = '1h' | '24h' | '7d' | '30d' | 'custom'
export const AUDIT_RANGE_PRESETS: AuditRangePreset[] = ['1h', '24h', '7d', '30d', 'custom']

const PRESET_HOURS: Record<Exclude<AuditRangePreset, 'custom'>, number> = {
  '1h': 1,
  '24h': 24,
  '7d': 24 * 7,
  '30d': 24 * 30,
}

/**
 * Resolves a preset into the real `from` instant the filter bar sends —
 * `custom` resolves to `{}`, deferring entirely to whatever the caller's
 * own From/To inputs hold. `now` is a parameter (default `new Date()`)
 * rather than read internally so a test can pass a fixed instant.
 */
export function resolveRangePreset(
  preset: AuditRangePreset,
  now: Date = new Date(),
): { from?: string } {
  if (preset === 'custom') return {}
  const hours = PRESET_HOURS[preset]
  return { from: new Date(now.getTime() - hours * 60 * 60 * 1000).toISOString() }
}

export async function listAuditEvents(
  slug: string,
  filters: AuditFilters,
  page: number,
  size: number = DEFAULT_AUDIT_PAGE_SIZE,
): Promise<AdminResult<PageView<AuditEventView>>> {
  const params = new URLSearchParams()
  if (filters.eventType) params.set('event_type', filters.eventType)
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  if (filters.userId) params.set('user_id', filters.userId)
  if (filters.clientId) params.set('client_id', filters.clientId)
  if (filters.actor) params.set('actor', filters.actor)
  params.set('page', String(page))
  params.set('size', String(size))

  const result = await tenantAdminFetch(slug, `/audit-events?${params.toString()}`)
  return parseAdminResult<PageView<AuditEventView>>(result)
}

// ---- domain-truth helpers ----------------------------------------------

/**
 * audit.invalid_* 400s (AuditQueryParams.java) carry an empty exception
 * context — no `errors` map, only a `code` (problem.ts's `error`
 * kind, UI-3e's `code` addition) — so this maps each onto the filter field
 * it concerns, same shape as tenantAdminRoles.ts's TENANT_ROLE_CONFLICT_FIELDS.
 */
export const AUDIT_ERROR_FIELDS: Record<string, string> = {
  'audit.invalid_event_type': 'eventType',
  'audit.invalid_from': 'from',
  'audit.invalid_to': 'to',
  'audit.invalid_user_id': 'userId',
  'audit.invalid_actor': 'actor',
}

/**
 * The full AuditEventType taxonomy (audit/enums/AuditEventType.java),
 * transcribed and grouped by the enum's own section comments — NOT
 * alphabetized or reorganized, so a diff against the backend source stays
 * legible. `notYetEmitted: true` groups mark the 8 documented exclusions
 * (AuditEventType's javadoc, "Not-yet-emitted values") — a filter selecting
 * one of these always returns an empty page, which the audit view surfaces
 * by putting them in their own, clearly-labelled <optgroup> rather than
 * silently mixing them into the working list.
 */
export interface AuditEventTypeGroup {
  label: string
  types: string[]
  notYetEmitted?: boolean
}

export const AUDIT_EVENT_TYPE_GROUPS: AuditEventTypeGroup[] = [
  {
    label: 'Authentication',
    types: [
      'USER_LOGIN_SUCCESS',
      'USER_LOGIN_FAILURE',
      'USER_LOGOUT',
      'TOKEN_ISSUED',
      'TOKEN_REVOKED',
      'TOKEN_INTROSPECTED',
      'REFRESH_TOKEN_ROTATED',
      'REFRESH_TOKEN_REPLAY_DETECTED',
      'REFRESH_TOKEN_REJECTED_TENANT_INACTIVE',
    ],
  },
  {
    label: 'One-time-token flows',
    types: [
      'EMAIL_VERIFICATION_ISSUED',
      'EMAIL_VERIFIED',
      'MAGIC_LINK_ISSUED',
      'PASSWORD_RESET_REQUESTED',
      'PASSWORD_RESET_COMPLETED',
      'ONE_TIME_TOKEN_CONSUME_FAILED',
      'INVITE_ISSUED',
      'INVITE_REVOKED',
    ],
  },
  {
    label: 'Session',
    types: ['SESSION_CREATED', 'SESSION_REVOKED'],
  },
  {
    label: 'Identity',
    types: ['USER_CREATED', 'USER_SUSPENDED', 'USER_ACTIVATED', 'USER_DELETED', 'PASSWORD_CHANGED'],
  },
  {
    label: 'Tenant',
    types: [
      'TENANT_CREATED',
      'TENANT_ACTIVATED',
      'TENANT_SUSPENDED',
      'TENANT_DELETED',
      'TENANT_BRANDING_CHANGED',
      'TENANT_REGISTRATION_POLICY_CHANGED',
    ],
  },
  {
    label: 'Authorization',
    types: ['ROLE_ASSIGNED', 'ROLE_REVOKED', 'CONSENT_GRANTED', 'CONSENT_REVOKED'],
  },
  {
    label: 'Client',
    types: ['CLIENT_REGISTERED', 'CLIENT_SECRET_REGENERATED'],
  },
  {
    label: 'Resource Server',
    types: ['RESOURCE_SERVER_CREATED', 'SCOPE_DEFINED', 'SCOPE_REMOVED'],
  },
  {
    label: 'Administrative',
    types: ['ADMIN_LOGIN', 'PLATFORM_CONFIGURATION_CHANGED'],
  },
  {
    label: 'Not yet emitted — always returns 0 events',
    notYetEmitted: true,
    types: [
      'USER_UPDATED',
      'CLIENT_UPDATED',
      'CLIENT_DELETED',
      'MFA_ENROLLED',
      'MFA_REMOVED',
      'AGENT_REGISTERED',
      'AGENT_REVOKED',
      'AGENT_CONSENT_GRANTED',
      'AGENT_TOKEN_EXCHANGED',
    ],
  },
]

/**
 * Resolves who acted, honestly — an admin-driven mutation has actorUserId
 * absent (the actor is a platform admin, carried in actorPlatformAdminId
 * instead), which must never render as "unknown user". Order matters:
 * actorUserId (a tenant user acted) -> actorPlatformAdminId (CloseAuth
 * staff acted) -> actorClientRegisteredId (an M2M client acted) -> "System"
 * (a background process — e.g. the outbox drain worker's own bookkeeping,
 * or a genuinely actorless event) only when none of the three is present.
 */
export function describeAuditActor(event: AuditEventView): string {
  if (event.actorUserId) return `User ${event.actorUserId}`
  if (event.actorPlatformAdminId) return 'CloseAuth platform admin'
  if (event.actorClientRegisteredId) return `Client ${event.actorClientRegisteredId}`
  return 'System'
}
