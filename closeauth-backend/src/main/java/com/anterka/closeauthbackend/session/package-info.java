/**
 * Authorization Server session model: tenant-scoped, Redis-backed SSO sessions via
 * Spring Session.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Tenant-scoped Auth Server sessions enabling silent SSO across a tenant's
 *       client apps; no single session spans tenants.</li>
 *   <li>Opaque session id in the cookie with server-side state in Redis.</li>
 *   <li>Idle (1h) and absolute (12h) timeouts, optional 30-day remember-me
 *       (per-tenant configurable in Phase 2, fixed in MVP).</li>
 *   <li>Per-device session listing/revocation and OIDC RP-initiated + back-channel
 *       logout.</li>
 * </ul>
 *
 * <p>References: Section 7.5 and 8.9 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.session;
