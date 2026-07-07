package com.anterka.closeauthbackend.platform.dto;

import java.util.UUID;

/**
 * Result of {@code PlatformAdminService.authenticate} — the platform-admin analogue of 3b's
 * {@code PasswordVerificationResult}. <b>Enumeration-safe:</b> every failure is the same shape ({@code success=false},
 * {@code adminId=null}); the {@link FailureReason} is for server-side audit only and MUST NOT be surfaced (the caller
 * collapses all failures to one generic "invalid credentials").
 */
public record PlatformAdminAuthResult(boolean success, UUID adminId, FailureReason reason) {

    /** Internal-only failure classification (audit). */
    public enum FailureReason { NONE, NOT_FOUND, NOT_ACTIVE, BAD_PASSWORD }

    public static PlatformAdminAuthResult success(UUID adminId) {
        return new PlatformAdminAuthResult(true, adminId, FailureReason.NONE);
    }

    public static PlatformAdminAuthResult failure(FailureReason reason) {
        return new PlatformAdminAuthResult(false, null, reason);
    }
}
