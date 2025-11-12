package com.anterka.closeauthbackend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UserResendOtpDto(
        @NotBlank(message = "Email is required")
        @Email(message = "Please provide a valid email address")
        String email
) {
}
