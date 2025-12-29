package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.enums.VerificationType;

import java.util.Set;

/**
 * Strategy interface for handling different verification modes during registration.
 * Each implementation defines how to initiate verification and what types are required.
 */
public interface VerificationStrategy {

    /**
     * Initiates the verification process for the registration.
     * This may send email OTP, SMS OTP, queue for admin approval, etc.
     *
     * @param registrationData The registration data containing user info
     */
    void initiate(RegistrationData registrationData);

    /**
     * Returns the set of verification types required by this strategy.
     *
     * @return Set of VerificationType values that must be completed
     */
    Set<VerificationType> getRequiredVerificationTypes();

    /**
     * Checks if this strategy requires immediate persistence (like AUTO_APPROVE).
     *
     * @return true if user should be persisted immediately after registration
     */
    default boolean requiresImmediatePersistence() {
        return false;
    }
}

