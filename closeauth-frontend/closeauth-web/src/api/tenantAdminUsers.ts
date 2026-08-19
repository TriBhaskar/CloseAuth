// Stage UI-3b: the tenant-admin console's user CRUD/lifecycle API layer,
// talking to internal/server/handlers_admin_users.go's
// /t/{slug}/api/users/** surface. Built on tenantAdminFetch
// (tenantAdminClient.ts) and parseAdminResult (problem.ts) —
// never apiClient, whose blanket 401 handling is wrong for this session.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'
import type { PageView } from '@/api/pageView'
export type { PageView } from '@/api/pageView'

// Mirrors identity/enums/UserStatus.java exactly.
export type UserStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'DELETED'

// Mirrors identity/dto/UserView.java exactly — every user endpoint returns
// this shape. Never carries credential material.
//
// FE-4a additions, both server-decorated (never computed client-side):
// `roles` is `[]` (never `undefined`) when the list endpoint decorates it —
// TenantUserController.list always populates it from a bulk read. `isLast
// ActiveAdmin` is `null` unless the SINGLE-user GET populated it (the list
// doesn't — its Actions column offers navigation, not lifecycle) — `null`
// means "not computed", never treated as "false".
export interface UserView {
  id: string
  tenantId: string
  email: string
  emailVerified: boolean
  phone: string | null
  phoneVerified: boolean
  firstName: string | null
  lastName: string | null
  status: UserStatus
  lastLoginAt: string | null
  createdAt: string
  updatedAt: string
  roles: string[]
  isLastActiveAdmin: boolean | null
}

export const DEFAULT_PAGE_SIZE = 20

export interface CreateUserPayload {
  email: string
  password: string
  firstName?: string
  lastName?: string
  phone?: string
  // Only PENDING or ACTIVE are accepted (identity/service/UserService.java's
  // resolveInitialStatus) — anything else is a 400 user.invalid_initial_status.
  initialStatus?: 'PENDING' | 'ACTIVE'
}

/**
 * FE-4a: the temporary-password create mode (spec §6.4.2) — a distinct
 * backend endpoint (POST /users/with-temp-credential), not a flag on
 * CreateUserPayload above. No password field: the server generates one.
 * `initialRoleId` is optional and, when present, assigned in the SAME
 * backend transaction as user creation (never a separate assignRole call).
 */
export interface CreateUserWithTempCredentialPayload {
  email: string
  firstName?: string
  lastName?: string
  phone?: string
  initialRoleId?: string
}

// Mirrors auth/dto/TenantAdminBootstrappedView.java exactly — the same
// shape the platform surface's bootstrap flow already returns (see
// api/platformAdminTenantUsers.ts's identically-named type), now also
// reachable from the tenant-admin surface. temporaryPassword is the ONLY
// place the raw value ever appears (write-once) — never persisted, never
// logged, cleared from memory once the operator dismisses the SecretRevealPanel.
export interface TenantAdminBootstrappedView {
  user: UserView
  temporaryPassword: string
  temporaryPasswordExpiresAt: string
}

/** FE-4a: status/role/q are each optional — omit to skip that filter, matching TenantUserController.list. */
export interface UserListFilters {
  status?: UserStatus
  role?: string
  q?: string
}

export async function listUsers(
  slug: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
  filters: UserListFilters = {},
): Promise<AdminResult<PageView<UserView>>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.status) params.set('status', filters.status)
  if (filters.role) params.set('role', filters.role)
  if (filters.q) params.set('q', filters.q)
  const result = await tenantAdminFetch(slug, `/users?${params.toString()}`)
  return parseAdminResult<PageView<UserView>>(result)
}

export async function getUser(slug: string, userId: string): Promise<AdminResult<UserView>> {
  const result = await tenantAdminFetch(slug, `/users/${encodeURIComponent(userId)}`)
  return parseAdminResult<UserView>(result)
}

export async function createUser(slug: string, payload: CreateUserPayload): Promise<AdminResult<UserView>> {
  const result = await tenantAdminFetch(slug, '/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<UserView>(result)
}

/** FE-4a: the temporary-password create mode — see CreateUserWithTempCredentialPayload's own doc comment. */
export async function createUserWithTempCredential(
  slug: string,
  payload: CreateUserWithTempCredentialPayload,
): Promise<AdminResult<TenantAdminBootstrappedView>> {
  const result = await tenantAdminFetch(slug, '/users/with-temp-credential', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<TenantAdminBootstrappedView>(result)
}

async function lifecycleAction(
  slug: string,
  userId: string,
  action: 'suspend' | 'activate' | 'approve',
): Promise<AdminResult<UserView>> {
  const result = await tenantAdminFetch(slug, `/users/${encodeURIComponent(userId)}/${action}`, { method: 'POST' })
  return parseAdminResult<UserView>(result)
}

/** Kills the user's live access tokens immediately (token revocation marker) — not just a status flip. */
export function suspendUser(slug: string, userId: string): Promise<AdminResult<UserView>> {
  return lifecycleAction(slug, userId, 'suspend')
}

/** Reactivates a SUSPENDED (or PENDING) user. Use approveUser for a PENDING user instead — same backend effect, but approve refuses a non-PENDING user with a clearer 409. */
export function activateUser(slug: string, userId: string): Promise<AdminResult<UserView>> {
  return lifecycleAction(slug, userId, 'activate')
}

/** PENDING -> ACTIVE only; 409 user.not_pending on any other status. */
export function approveUser(slug: string, userId: string): Promise<AdminResult<UserView>> {
  return lifecycleAction(slug, userId, 'approve')
}

/** Soft-delete — terminal; kills live access tokens immediately. */
export async function deleteUser(slug: string, userId: string): Promise<AdminResult<UserView>> {
  const result = await tenantAdminFetch(slug, `/users/${encodeURIComponent(userId)}`, { method: 'DELETE' })
  return parseAdminResult<UserView>(result)
}

export type UserLifecycleAction = 'approve' | 'activate' | 'suspend' | 'delete'

/**
 * The real transition matrix (identity/service/UserStateMachine.java):
 *   PENDING   -> ACTIVE (via approve) | DELETED
 *   ACTIVE    -> SUSPENDED | DELETED
 *   SUSPENDED -> ACTIVE (via activate, NOT approve — approve requires PENDING) | DELETED
 *   DELETED   -> terminal, no transitions out
 * The detail view offers exactly these actions per status, so the UI never
 * presents an action the backend would refuse with a 409
 * user.invalid_state_transition / user.not_pending.
 */
export function availableActions(status: UserStatus): UserLifecycleAction[] {
  switch (status) {
    case 'PENDING':
      return ['approve', 'delete']
    case 'ACTIVE':
      return ['suspend', 'delete']
    case 'SUSPENDED':
      return ['activate', 'delete']
    case 'DELETED':
      return []
  }
}
