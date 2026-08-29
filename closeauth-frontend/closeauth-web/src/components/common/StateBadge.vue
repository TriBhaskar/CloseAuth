<script setup lang="ts">
// FE-1.5 (spec §3.6): "One component, one vocabulary, everywhere." Five
// domains share this one component and its tone vocabulary rather than each
// growing its own badge (the bug UserStatusBadge.vue was — narrow, single-
// purpose, and exactly what this replaces).
//
// Two true-enum domains (Tenant, User) get an exported, exhaustively-typed
// tone helper below — an unhandled status value is a COMPILE error, not a
// blank badge. The other three domains in spec §3.6's table are templated
// strings, not enums, so there's nothing to exhaustively switch over; the
// caller pre-resolves { tone, label } itself. One example per domain so a
// future caller doesn't have to re-derive the mapping from the spec:
//   - Credential: `must_change_password=true` → { tone: 'warn', label: 'Must change password' }
//   - Client:     a rotated secret               → { tone: 'muted', label: 'Secret rotated 3d ago' }
//   - Session:    the current device              → { tone: 'accent', label: 'Current device' }
import type { TenantStatus } from '@/api/platformAdminTenants'
import type { UserStatus } from '@/api/tenantAdminUsers'
import type { PlatformAdminStatus } from '@/api/platformAdmins'
import type { AuditEventView } from '@/api/tenantAdminAudit'

export type Tone = 'warn' | 'ok' | 'danger' | 'muted' | 'accent'

defineProps<{
  tone: Tone
  label: string
}>()

// Full, literal class strings — never built from a template literal. See
// IdentifierChip.vue's identical note: Tailwind's scanner needs the whole
// class name present as source text, not assembled at runtime.
const TONE_CLASSES: Record<Tone, string> = {
  warn: 'bg-warn-wash text-warn border-transparent',
  ok: 'bg-ok-wash text-ok border-transparent',
  danger: 'bg-danger-wash text-danger border-transparent',
  accent: 'bg-accent-wash text-primary border-transparent',
  // §3.6: "no fill in the muted tone (border only) — filled badges
  // everywhere turn a table into confetti."
  muted: 'bg-transparent text-ink-muted border-line-strong',
}

function toneClass(t: Tone): string {
  return TONE_CLASSES[t]
}
</script>

<script lang="ts">
function assertNever(value: never): never {
  throw new Error(`Unhandled state value: ${JSON.stringify(value)}`)
}

// Tenant domain (spec §3.6): PROVISIONING/ACTIVE/SUSPENDED/DELETED — matches
// the actual TenantStatus type exactly.
export function tenantStatusTone(status: TenantStatus): Tone {
  switch (status) {
    case 'PROVISIONING':
      return 'warn'
    case 'ACTIVE':
      return 'ok'
    case 'SUSPENDED':
      return 'danger'
    case 'DELETED':
      return 'muted'
    default:
      return assertNever(status)
  }
}

// User domain: spec §3.6 documents PENDING/ACTIVE/SUSPENDED/DEACTIVATED, but
// the actual UserStatus type in this codebase is PENDING/ACTIVE/SUSPENDED/
// DELETED (api/tenantAdminUsers.ts) — a real spec-vs-implementation
// vocabulary mismatch, not something this helper can silently paper over.
// DELETED is mapped to the same 'muted' tone spec's DEACTUATED would get
// (both are a terminal, inactive state); flagged here rather than guessed
// past silently.
export function userStatusTone(status: UserStatus): Tone {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'ACTIVE':
      return 'ok'
    case 'SUSPENDED':
      return 'danger'
    case 'DELETED':
      return 'muted'
    default:
      return assertNever(status)
  }
}

// FE-3c: platform-admin domain. A strict subset of TenantStatus (no
// PROVISIONING) but its own type — deliberately not reusing tenantStatusTone
// against a structurally-similar-but-different domain.
export function platformAdminStatusTone(status: PlatformAdminStatus): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'ok'
    case 'SUSPENDED':
      return 'danger'
    case 'DELETED':
      return 'muted'
    default:
      return assertNever(status)
  }
}

// FE-5.1: audit domain. AuditEventView.outcome (tenantAdminAudit.ts) mirrors
// audit/enums/AuditOutcome.java exactly — SUCCESS/FAILURE/ERROR, no more —
// so an unhandled outcome value is a compile error, not a blank badge,
// exactly like the two enum domains above.
export function auditOutcomeTone(outcome: AuditEventView['outcome']): Tone {
  switch (outcome) {
    case 'SUCCESS':
      return 'ok'
    case 'FAILURE':
      return 'warn'
    case 'ERROR':
      return 'danger'
    default:
      return assertNever(outcome)
  }
}
</script>

<template>
  <span
    class="inline-flex items-center border rounded px-1.5 py-0.5 text-[0.6875rem] font-medium uppercase tracking-[0.08em] whitespace-nowrap"
    :class="toneClass(tone)"
  >
    {{ label }}
  </span>
</template>
