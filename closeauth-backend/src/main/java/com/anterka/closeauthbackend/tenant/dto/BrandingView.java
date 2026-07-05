package com.anterka.closeauthbackend.tenant.dto;

/**
 * The <b>public</b> branding resolution response for the hosted pages (§7.1, Stage 6b-ii). Deliberately contains ONLY
 * non-sensitive presentation fields — NO tenant internals (no {@code tenantId}, status, counts, or any identifier).
 * This is the exact, safe shape the (unauthenticated, pre-login) resolution endpoint returns; it must never grow a
 * field that leaks tenant internals.
 *
 * <p>Null tenant fields are filled with platform defaults before this is built, so every field is renderable
 * ({@code logoUrl}/{@code companyName} may still be null when neither the tenant nor the platform default sets them —
 * the UI renders a generic sign-in in that case).
 */
public record BrandingView(
        String logoUrl,
        String primaryColor,
        String backgroundColor,
        String accentColor,
        String companyName) {
}
