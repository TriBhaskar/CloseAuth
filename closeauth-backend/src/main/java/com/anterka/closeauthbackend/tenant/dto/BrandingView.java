package com.anterka.closeauthbackend.tenant.dto;

import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;

/**
 * The <b>public</b> branding resolution response for the hosted pages (§7.1, Stage 6b-ii). Deliberately contains ONLY
 * non-sensitive presentation fields — NO tenant internals (no {@code tenantId} UUID, status, counts, or any other
 * identifier that would let an unauthenticated caller enumerate/probe tenant state). This is the exact, safe shape
 * the (unauthenticated, pre-login) resolution endpoint returns; it must never grow a field that leaks tenant
 * internals.
 *
 * <p>{@code tenantSlug} (BE-B) and {@code registrationMode} (FE-2b) are the two deliberate exceptions, not a
 * relaxation of the rule above:
 * <ul>
 *   <li>{@code tenantSlug} is the tenant's PUBLIC Tenant ID (spec §1.2 — "everyone" sees it; it's meant to be pasted
 *       into URLs and support tickets), already visible in the hosted-auth page's own URL ({@code /t/{slug}/...}) the
 *       moment branding is fetched. Exposing it here teaches an unauthenticated caller nothing its own address bar
 *       doesn't already show. It exists so ConsentView.vue's two-hop redirect can route from SAS's fixed,
 *       un-namespaced {@code /consent} landing to {@code /t/{slug}/consent} without a second lookup.</li>
 *   <li>{@code registrationMode} is whether self-registration is open at all — not a secret (the {@code /register}
 *       route's own reachability, spec §6.2.3, already reveals this to anyone who tries it: an {@code INVITE_ONLY}/
 *       {@code ADMIN_APPROVED} tenant 404s the route outright). Exposing it here lets the LOGIN page's
 *       {@code Create an account} link (spec §6.2.2) decide its own visibility without a second unauthenticated
 *       lookup.</li>
 * </ul>
 *
 * <p>Null tenant fields are filled with platform defaults before this is built, so every field is renderable
 * ({@code logoUrl}/{@code companyName} may still be null when neither the tenant nor the platform default sets them —
 * the UI renders a generic sign-in in that case). {@code tenantSlug} and {@code registrationMode} are null only for
 * platform-default branding (an unknown {@code client_id} — there is no tenant to resolve either from).
 */
public record BrandingView(
        String logoUrl,
        String primaryColor,
        String backgroundColor,
        String accentColor,
        String companyName,
        String tenantSlug,
        RegistrationMode registrationMode) {
}
