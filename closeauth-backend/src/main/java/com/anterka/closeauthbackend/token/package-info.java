/**
 * Token layer: issuance, refresh-token rotation with replay detection,
 * introspection, and the Redis-backed revocation list.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Access tokens with the canonical claim shape (Section 12): {@code tenant_id},
 *       stable {@code sub}, RS {@code aud}, {@code idp}, three-tier role claims, and
 *       a reserved {@code act} delegation claim.</li>
 *   <li>Short access-token TTL (5 min default) plus refresh tokens (14 day sliding)
 *       stored with {@code family_id}/{@code parent_token_id} for rotation.</li>
 *   <li>Replay detection: presenting a {@code USED} refresh token revokes the entire
 *       token family and raises a high-priority audit event.</li>
 *   <li>Introspection-backed instant revocation via a Redis denylist keyed by user
 *       id (TTL = access-token TTL), consulted by {@code /oauth2/introspect}.</li>
 * </ul>
 *
 * <p>References: Sections 7.3, 7.7 ({@code act}), and 12 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.token;
