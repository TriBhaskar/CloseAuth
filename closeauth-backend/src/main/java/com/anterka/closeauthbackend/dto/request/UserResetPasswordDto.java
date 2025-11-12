package com.anterka.closeauthbackend.dto.request;

public record UserResetPasswordDto(
        String token,
        String newPassword,
        String confirmPassword
) {
}
