package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * {@code TenantOnboardingService.reissueOnboardingCredential} refused because the target user has no pending
 * temp credential (Phase 3, §2.9 decision) — either they have no {@code LOCAL_PASSWORD} identity, or they already
 * completed rotation. Reissue is a "the onboarding credential never got used" recovery tool, not a way to force a
 * working admin account back into rotation; an already-rotated admin uses ordinary self-service password reset
 * instead. Category {@link ErrorCategory#CONFLICT}.
 */
public class NoPendingTempCredentialException extends CloseAuthDomainException {

    private static final String CODE = "tenant_onboarding.no_pending_temp_credential";

    public NoPendingTempCredentialException(UUID userId) {
        super(ErrorCategory.CONFLICT, CODE,
                "User " + userId + " has no pending temporary credential to reissue",
                Map.of("userId", userId.toString()));
    }
}
