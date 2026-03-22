package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.auth.enums.VerificationMode;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;

import java.util.Set;

/**
 * Data stored in Redis during registration flow.
 * Contains all information needed to complete registration after verification.
 */
public record RegistrationData(
        UserRegistrationDto registrationDto,
        GlobalRoleEnum globalRoleEnum,
        String clientId,
        VerificationMode verificationMode,
        Set<VerificationType> pendingVerifications
) {
    /**
     * Legacy constructor for backward compatibility
     */
    public RegistrationData(UserRegistrationDto registrationDto, GlobalRoleEnum globalRoleEnum) {
        this(registrationDto, globalRoleEnum, null, VerificationMode.EMAIL, Set.of(VerificationType.EMAIL));
    }

    /**
     * Check if all verifications are complete
     */
    public boolean isVerificationComplete() {
        return pendingVerifications == null || pendingVerifications.isEmpty();
    }

    /**
     * Create a new RegistrationData with a verification removed
     */
    public RegistrationData withVerificationCompleted(VerificationType type) {
        Set<VerificationType> remaining = new java.util.HashSet<>(pendingVerifications);
        remaining.remove(type);
        return new RegistrationData(registrationDto, globalRoleEnum, clientId, verificationMode, remaining);
    }
}
