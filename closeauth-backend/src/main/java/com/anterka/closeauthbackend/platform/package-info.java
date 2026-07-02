/**
 * Platform-administration concerns owned by CloseAuth-the-platform itself, not by
 * any single tenant.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Platform-global RSA signing keys and JWKS (current + previous {@code kid}s
 *       for non-disruptive rotation).</li>
 *   <li>Platform-level admin users (CloseAuth staff) and system configuration.</li>
 *   <li>The single {@code iss} value and the reserved per-tenant issuer URL pattern
 *       ({@code https://auth.closeauth.io/t/{slug}}).</li>
 *   <li>Billing plan catalog ownership (platform-owned, not tenant-owned).</li>
 * </ul>
 *
 * <p>References: Sections 7.1 (platform-owned vs. tenant-owned), 7.2 (signing key
 * strategy), and 10.3 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.platform;
