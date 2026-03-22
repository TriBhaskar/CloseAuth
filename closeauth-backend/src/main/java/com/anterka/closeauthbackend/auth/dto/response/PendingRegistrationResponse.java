package com.anterka.closeauthbackend.auth.dto.response;

import lombok.Builder;

/**
 * Response DTO for pending registration data displayed to admins.
 */
@Builder
public record PendingRegistrationResponse(
        String email,
        String username,
        String firstName,
        String lastName,
        String phone,
        String verificationMode
) {
}

