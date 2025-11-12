package com.anterka.closeauthbackend.dto.response;

import java.time.LocalDateTime;

public record UserRegistrationResponse(
        Long userId,
        String email,
        String firstName,
        String lastName,
        String message,
        long otpValiditySeconds,
        LocalDateTime timestamp
) {
    public static UserRegistrationResponse success(Long userId, String email, String firstName, String lastName, long otpValiditySeconds) {
        return new UserRegistrationResponse(
                userId,
                email,
                firstName,
                lastName,
                "Registration successful. Please verify your email to activate your account.",
                otpValiditySeconds,
                LocalDateTime.now()
        );
    }
}
