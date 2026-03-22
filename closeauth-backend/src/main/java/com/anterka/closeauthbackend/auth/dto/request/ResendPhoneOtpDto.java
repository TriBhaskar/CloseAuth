package com.anterka.closeauthbackend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for resend phone OTP request.
 */
public record ResendPhoneOtpDto(
        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "Email is required")
        String email
) {
}

