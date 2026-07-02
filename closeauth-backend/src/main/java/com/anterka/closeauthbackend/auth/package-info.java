/**
 * Interactive authentication flows: login, self-registration, OTP/email
 * verification, and password reset.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Registration modes configurable per tenant (open, email-verified,
 *       admin-approved, invite-only) via the strategy pattern.</li>
 *   <li>Email verification and password-reset tokens persisted in the database
 *       (single-use, hashed at rest) rather than Redis-only.</li>
 *   <li>Abuse protection: rate limiting and lockout on login, registration, OTP,
 *       and reset.</li>
 * </ul>
 *
 * <p>References: Sections 8.1, 8.5, 13.3, and 13.4 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.auth;
