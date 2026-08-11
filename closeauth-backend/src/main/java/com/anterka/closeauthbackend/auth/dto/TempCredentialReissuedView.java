package com.anterka.closeauthbackend.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of {@code TenantOnboardingService.reissueOnboardingCredential} (Phase 3, §2.7/§2.9). Write-once, same
 * convention as {@link TenantAdminBootstrappedView} — the only place the fresh raw temporary password appears.
 *
 * @param userId                       the existing user whose temp credential was reissued
 * @param temporaryPassword            the raw, fresh, system-generated temporary password — shown exactly once
 * @param temporaryPasswordExpiresAt   the new hard expiry (§2.3)
 */
public record TempCredentialReissuedView(UUID userId, String temporaryPassword, Instant temporaryPasswordExpiresAt) {
}
