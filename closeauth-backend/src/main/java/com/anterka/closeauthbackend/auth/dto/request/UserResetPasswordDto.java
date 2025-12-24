package com.anterka.closeauthbackend.auth.dto.request;

public record UserResetPasswordDto(
        String token,
        String newPassword,
        String confirmPassword
) {
}
