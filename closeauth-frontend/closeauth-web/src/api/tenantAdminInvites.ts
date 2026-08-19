// FE-4a: the invitation create mode (spec §6.4.2) — talking to
// internal/server/handlers_admin_invites.go's /t/{slug}/api/invites surface.
// Java's InviteController already existed (issue/list/revoke); this is the
// first console route reaching it. Built on tenantAdminFetch/parseAdminResult,
// same as every other tenant-admin api module.
import { tenantAdminFetch } from '@/api/tenantAdminClient'
import { parseAdminResult, type AdminResult } from '@/api/problem'

// Mirrors auth/dto/InviteView.java exactly. Deliberately carries no secret —
// the raw invite token is emailed only, never returned in any response.
export interface InviteView {
  id: string
  email: string
  expiresAt: string
  createdAt: string
}

// Mirrors auth/dto/IssueInviteCommand.java exactly — email only. The invitee
// sets their own name and password at registration time; no initial-role
// field exists here (an invite creates no user row until the token is
// consumed, so there's no user yet to assign a role to — roles are assigned
// afterward, as ordinary tenant-role assignment).
export interface IssueInvitePayload {
  email: string
}

/** Outstanding (unconsumed, unexpired) invites for this tenant. */
export async function listInvites(slug: string): Promise<AdminResult<InviteView[]>> {
  const result = await tenantAdminFetch(slug, '/invites')
  return parseAdminResult<InviteView[]>(result)
}

export async function issueInvite(slug: string, payload: IssueInvitePayload): Promise<AdminResult<InviteView>> {
  const result = await tenantAdminFetch(slug, '/invites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseAdminResult<InviteView>(result)
}

/** Revokes an outstanding invite before it's consumed. */
export async function revokeInvite(slug: string, inviteId: string): Promise<AdminResult<void>> {
  const result = await tenantAdminFetch(slug, `/invites/${encodeURIComponent(inviteId)}`, { method: 'DELETE' })
  return parseAdminResult<void>(result)
}
