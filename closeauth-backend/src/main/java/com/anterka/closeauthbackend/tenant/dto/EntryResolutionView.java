package com.anterka.closeauthbackend.tenant.dto;

import com.anterka.closeauthbackend.tenant.enums.TenantStatus;

/**
 * FE-2a (spec §6.1): the public, unauthenticated workspace-entry resolution response
 * ({@code GET /entry/resolve?tenantId=...}). Deliberately minimal — like {@link BrandingView},
 * this is a pre-authentication, existence-sensitive surface, so it carries only what the entry
 * screen needs to render (no internal {@code id} UUID, no timestamps, no admin count).
 *
 * <p>{@code tenantId} echoes the caller's own (already-validated, {@code ten_}-prefixed) input
 * rather than a separately derived value — there is exactly one field carrying the public Tenant
 * ID anywhere in this response, matching how the rest of the public/admin surface treats it.
 *
 * <p>{@code status} is included per spec's literal response shape, but {@link TenantService}
 * only ever returns this view for an {@code ACTIVE} tenant — every other status (or no tenant at
 * all) is a 404, never a 200 carrying a non-ACTIVE status. See {@code TenantService.resolveActiveTenantBySlug}.
 */
public record EntryResolutionView(String tenantId, String displayName, TenantStatus status) {
}
