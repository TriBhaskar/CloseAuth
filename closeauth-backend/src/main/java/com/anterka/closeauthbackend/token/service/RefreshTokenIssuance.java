package com.anterka.closeauthbackend.token.service;

import java.time.Instant;

/**
 * Per-token attributes recorded in the refresh-token ledger when a token is issued or rotated.
 *
 * @param tokenHash  SHA-256 hash of the raw refresh token (the raw value is never stored)
 * @param scopes     space-delimited granted scopes
 * @param expiresAt  the token's expiry — {@code now + 14 days} (the §7.3 sliding window; sourced from SAS's
 *                   {@code TokenSettings} refresh TTL, so each rotation issues a fresh token with a fresh clock)
 * @param ipAddress  where the token was issued from (nullable)
 * @param userAgent  the issuing user agent (nullable)
 */
public record RefreshTokenIssuance(
        String tokenHash,
        String scopes,
        Instant expiresAt,
        String ipAddress,
        String userAgent
) {}
