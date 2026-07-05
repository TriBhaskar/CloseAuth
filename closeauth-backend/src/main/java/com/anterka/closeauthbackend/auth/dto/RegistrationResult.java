package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;

import java.util.UUID;

/**
 * The outcome of a successful registration. Communicates the next step the UI should take (verify email, wait for
 * approval, or proceed to login).
 *
 * @param userId               the created user
 * @param status               the initial user status (ACTIVE, or PENDING for email-verified / admin-approved)
 * @param mode                 the registration mode that was applied
 * @param emailVerificationSent whether an email-verification code was dispatched (email-verified mode)
 */
public record RegistrationResult(UUID userId, UserStatus status, RegistrationMode mode, boolean emailVerificationSent) {
}
