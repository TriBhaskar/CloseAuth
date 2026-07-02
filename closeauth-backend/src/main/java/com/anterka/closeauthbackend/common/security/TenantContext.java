package com.anterka.closeauthbackend.common.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The tenant scope an operation runs within — the single typed value threaded
 * explicitly through tenant-owned-data services (identity, RBAC, resource server, …)
 * so tenant isolation is visible at every call site (Section 11).
 *
 * <p>This is deliberately NOT ambient state (no ThreadLocal / "current tenant" holder):
 * ambient tenant context is precisely the pattern that produces silent cross-tenant
 * leaks. Passing it explicitly keeps the tenant scope reviewable in code.
 *
 * <p>Immutable value object. Today it carries only {@code tenantId}; it is expected to
 * grow to also carry the acting principal's identity when audit wiring lands in Stage 8
 * (e.g. {@code actorUserId} / {@code actorClientId}). Growing this record is the intended
 * single place to add that, rather than adding parameters across every service method.
 *
 * <p>Note: <em>populating</em> this from an inbound request (resolving {@code client_id →
 * tenant_id}, or reading the {@code tenant_id} token claim) is a Stage 7 concern. This
 * type only defines the shape and how services consume it.
 *
 * <p><b>When to use {@code TenantContext} vs a bare {@code UUID tenantId} — the
 * "acted-on vs operated-within" rule:</b>
 * <ul>
 *   <li><b>Bare {@code UUID tenantId}</b> — the tenant is the <em>object being acted on</em>.
 *       These are platform-level or tenant-<em>targeting</em> operations performed on a tenant
 *       from the outside; the caller is not "inside" the tenant. Examples:
 *       {@code getTenantById(id)}, {@code suspendTenant(id)}, {@code activateTenant(id)},
 *       {@code deleteTenant(id)}. Cross-tenant / platform-admin operations are always bare UUID.</li>
 *   <li><b>{@code TenantContext}</b> — the tenant is the <em>boundary the operation happens
 *       inside</em>. These are within-tenant operations on the tenant's owned data, and the
 *       context will later also carry the acting principal for audit. Examples (3b/3c):
 *       {@code createUser(ctx, …)}, {@code assignRole(ctx, …)}, and the
 *       {@code requireActiveTenant(ctx)} guard.</li>
 * </ul>
 * Do NOT put {@code TenantContext} on platform-admin operations that target or cross tenants —
 * the admin is acting <em>on</em> the tenant from outside, not operating <em>within</em> it.
 * {@code TenantService}'s own methods are all tenant-as-object and therefore correctly take bare
 * {@code UUID} (except the downstream guard {@code requireActiveTenant(TenantContext)}).
 */
public record TenantContext(UUID tenantId) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    public static TenantContext of(UUID tenantId) {
        return new TenantContext(tenantId);
    }
}
