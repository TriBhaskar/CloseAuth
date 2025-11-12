package com.anterka.closeauthbackend.dto.response;

import java.time.LocalDateTime;

public record ResendOtpResponse(
        String message,
        long otpValiditySeconds,
        String email,
        LocalDateTime timestamp
) {
}
