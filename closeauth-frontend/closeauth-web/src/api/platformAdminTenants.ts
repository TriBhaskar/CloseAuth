// Stage UI-4: the platform console's tenant-lifecycle API layer, talking to
// internal/server/handlers_platform_tenants.go's /platform/api/tenants/**
// surface. Built on platformAdminFetch/parsePlatformResult — the
// cross-tenant sibling of tenantAdminUsers.ts.
import { platformAdminFetch } from '@/api/platformAdminClient'
import { parsePlatformResult } from '@/api/platformAdminProblem'
import type { AdminResult } from '@/api/problem'
import type { PageView } from '@/api/pageView'
export type { PageView } from '@/api/pageView'

// Mirrors tenant/enums/TenantStatus.java exactly.
export type TenantStatus = 'PROVISIONING' | 'ACTIVE' | 'SUSPENDED' | 'DELETED'

// Mirrors tenant/dto/TenantView.java exactly. adminCount is nullable —
// `null` means "not computed", NOT "unknown admin count coerced to zero".
// Only GET /tenants and GET /tenants/{id} populate it (PlatformTenantController
// joins TenantRoleService's active-admin count in); provision/activate/
// suspend/delete return a bare TenantView with adminCount left null, so never
// read it off a mutation response — re-list or re-get instead.
export interface TenantView {
  id: string
  slug: string
  name: string
  status: TenantStatus
  createdAt: string
  updatedAt: string
  deletedAt: string | null
  adminCount: number | null
}

export const DEFAULT_PAGE_SIZE = 20

export interface ProvisionTenantPayload {
  name: string
}

export async function listTenants(page: number, size: number = DEFAULT_PAGE_SIZE): Promise<AdminResult<PageView<TenantView>>> {
  const result = await platformAdminFetch(`/tenants?page=${page}&size=${size}`)
  return parsePlatformResult<PageView<TenantView>>(result)
}

/** FE-3b: single-tenant fetch for the detail page. Populates adminCount, same as listTenants' items. */
export async function getTenant(tenantId: string): Promise<AdminResult<TenantView>> {
  const result = await platformAdminFetch(`/tenants/${encodeURIComponent(tenantId)}`)
  return parsePlatformResult<TenantView>(result)
}

export async function provisionTenant(payload: ProvisionTenantPayload): Promise<AdminResult<TenantView>> {
  const result = await platformAdminFetch('/tenants', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parsePlatformResult<TenantView>(result)
}

async function lifecycleAction(tenantId: string, action: 'activate' | 'suspend'): Promise<AdminResult<TenantView>> {
  const result = await platformAdminFetch(`/tenants/${encodeURIComponent(tenantId)}/${action}`, { method: 'POST' })
  return parsePlatformResult<TenantView>(result)
}

/** PROVISIONING|SUSPENDED -> ACTIVE. */
export function activateTenant(tenantId: string): Promise<AdminResult<TenantView>> {
  return lifecycleAction(tenantId, 'activate')
}

/** ACTIVE -> SUSPENDED. Revokes every user's live access tokens AND SSO sessions in the tenant immediately. */
export function suspendTenant(tenantId: string): Promise<AdminResult<TenantView>> {
  return lifecycleAction(tenantId, 'suspend')
}

/** Soft-delete — terminal (DELETED has no transitions out); revokes live tokens immediately. */
export async function deleteTenant(tenantId: string): Promise<AdminResult<TenantView>> {
  const result = await platformAdminFetch(`/tenants/${encodeURIComponent(tenantId)}`, { method: 'DELETE' })
  return parsePlatformResult<TenantView>(result)
}

export type TenantLifecycleAction = 'activate' | 'suspend' | 'delete'

/**
 * The real transition matrix (tenant/service/TenantStateMachine.java):
 *   PROVISIONING -> ACTIVE (via activate) | DELETED
 *   ACTIVE       -> SUSPENDED | DELETED
 *   SUSPENDED    -> ACTIVE (via activate) | DELETED
 *   DELETED      -> terminal, no transitions out
 * The tenants view offers exactly these actions per status, so the UI never
 * presents an action the backend would refuse with a 409
 * tenant.invalid_state_transition.
 */
export function availableTenantActions(status: TenantStatus): TenantLifecycleAction[] {
  switch (status) {
    case 'PROVISIONING':
      return ['activate', 'delete']
    case 'ACTIVE':
      return ['suspend', 'delete']
    case 'SUSPENDED':
      return ['activate', 'delete']
    case 'DELETED':
      return []
  }
}
