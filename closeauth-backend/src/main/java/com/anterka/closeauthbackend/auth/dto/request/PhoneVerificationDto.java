package com.anterka.closeauthbackend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for phone OTP verification request.
 */
public record PhoneVerificationDto(
        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "OTP is required")
        String otp,

        @NotBlank(message = "Email is required")
        String email
) {
}

