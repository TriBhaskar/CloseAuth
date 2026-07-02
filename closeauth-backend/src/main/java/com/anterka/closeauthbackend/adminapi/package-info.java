/**
 * The public admin API: a first-class, versioned, OAuth2-authenticated product
 * surface. The admin dashboard UI and the Go BFF are just its first consumers.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Resource-oriented REST with tenant explicit in the path
 *       ({@code /v1/tenants/{tenant_id}/...}, {@code /v1/platform/...}, {@code /v1/me}).</li>
 *   <li>URL-level semver versioning ({@code /v1}, {@code /v2}).</li>
 *   <li>RFC 7807 Problem Details as the uniform error model.</li>
 *   <li>OAuth2-only authentication; the admin API is itself a platform-owned
 *       Resource Server ({@code closeauth-admin-api}). Every service method has a
 *       documented endpoint — no dead code.</li>
 *   <li>OpenAPI annotations from Phase 1 (public doc site is a Phase 2 deliverable).</li>
 * </ul>
 *
 * <p>Note: this module is named {@code adminapi} (the {@code admin-api} in the vision
 * tree is not a legal Java package identifier).
 *
 * <p>References: Section 7.8 and 8.11 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.adminapi;
