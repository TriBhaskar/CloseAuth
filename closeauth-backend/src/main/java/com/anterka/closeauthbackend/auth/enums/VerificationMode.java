package com.anterka.closeauthbackend.auth.enums;

/**
 * Verification modes for client-specific registration flows.
 * Each client can configure which verification method is required.
 */
public enum VerificationMode {
    /**
     * User must verify email before activation
     */
    EMAIL,

    /**
     * User must verify phone via SMS OTP before activation
     */
    PHONE,

    /**
     * Admin must manually approve user before activation
     */
    ADMIN_APPROVAL,

    /**
     * User must verify both email and phone before activation
     */
    EMAIL_AND_PHONE,

    /**
     * User is activated immediately after registration
     */
    AUTO_APPROVE
}

