/**
 * Identity: users and their credential sources, decoupled so that a user no longer
 * implies a password.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@code users} with per-tenant composite uniqueness ({@code (tenant_id, email)}),
 *       never global uniqueness.</li>
 *   <li>{@code user_identities} linking users to credential sources
 *       ({@code LOCAL_PASSWORD}, {@code SOCIAL_GOOGLE}, {@code OIDC_FEDERATED},
 *       {@code AGENT_KEY}, ...).</li>
 *   <li>Stable opaque {@code sub} (user UUID) as the token subject, never username
 *       or email.</li>
 *   <li>Federation preparation: {@code tenant_idp_connections} schema reserved
 *       (empty in MVP; implemented in Phase 2).</li>
 * </ul>
 *
 * <p>References: Sections 7.4 (federation), 11 (multi-tenancy), and 12 (stable sub)
 * of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.identity;
