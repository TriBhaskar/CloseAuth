// Stage UI-3b: the tenant-admin console's user CRUD/lifecycle API layer,
// talking to internal/server/handlers_admin_users.go's
// /t/{slug}/api/users/** surface. Built on tenantAdminFetch
// (tenantAdminClient.ts) and parseAdminResult (tenantAdminProblem.ts) —
// never apiClient, whose blanket 401 handling is wrong for this session.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/tenantAdminProblem'

// Mirrors identity/enums/UserStatus.java exactly.
export type UserStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'DELETED'

// Mirrors identity/dto/UserView.java exactly — every user endpoint returns
// this shape. Never carries credential material.
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
}

// Mirrors common/web/PageView.java exactly. Field is `items`, not `content`.
// Trust the response's echoed page/size over what was requested — the
// backend clamps size to [1, 100] and page to >= 0.
export interface PageView<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
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

export async function listUsers(
  slug: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<AdminResult<PageView<UserView>>> {
  const result = await tenantAdminFetch(slug, `/users?page=${page}&size=${size}`)
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
