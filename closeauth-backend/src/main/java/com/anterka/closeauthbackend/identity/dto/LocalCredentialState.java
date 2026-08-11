package com.anterka.closeauthbackend.identity.dto;

import java.time.Instant;

/**
 * The credential-lifecycle state of a user's {@code LOCAL_PASSWORD} identity (Phase 2 of the tenant-onboarding
 * design, {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md} §2.2/§2.3) — read by {@code LoginPolicyService}'s shared
 * credential-lifecycle gate, consulted by both {@code authenticate} (password login) and {@code isLoginAllowed}
 * (magic-link), after the caller has already proven (or been granted) identity.
 *
 * @param mustChangePassword     forced-rotation gate — while {@code true}, no session may be issued for this identity
 * @param tempCredentialExpiresAt hard expiry of a system-generated temp credential; {@code null} for a user-chosen
 *                                 password, which never expires on this axis
 */
public record LocalCredentialState(boolean mustChangePassword, Instant tempCredentialExpiresAt) {
}
