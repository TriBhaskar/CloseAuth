package com.anterka.closeauthbackend.auth.dto.request;

public record UserForgotPasswordDto(
        String email,
        String forgotPasswordLink
) {
}
