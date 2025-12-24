package com.anterka.closeauthbackend.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserEmailVerificationDto(
        @NotBlank(message = "Email is required")
        @Email(message = "Please provide a valid email address")
        String email,
        @NotBlank(message = "Otp is required")
        @Size(min = 6, max = 8, message = "Otp must be between 6 and 8 characters")
        String verificationCode
) {
}
