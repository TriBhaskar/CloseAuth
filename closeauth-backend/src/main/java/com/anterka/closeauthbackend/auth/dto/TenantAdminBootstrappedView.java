package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.identity.dto.UserView;

import java.time.Instant;

/**
 * Response of {@code TenantOnboardingService.bootstrapFirstAdmin} (Phase 3, §2.9). Write-once: the ONLY response
 * that ever carries the raw temporary password — mirrors the {@code ClientCreatedView}/{@code ClientView}
 * convention ({@link UserView}, like {@code ClientView}, carries no credential field of its own). The platform
 * admin who triggers issuance is trusted to relay or discard {@code temporaryPassword}; it is never logged and
 * never appears in any subsequent read of this user.
 *
 * @param user                          the newly-created admin (credential-free)
 * @param temporaryPassword             the raw, system-generated temporary password — shown exactly once
 * @param temporaryPasswordExpiresAt    hard expiry of the temporary credential (§2.3)
 */
public record TenantAdminBootstrappedView(UserView user, String temporaryPassword, Instant temporaryPasswordExpiresAt) {
}
