package com.anterka.closeauthbackend.dto.request;

public record UserForgotPasswordDto(
        String email,
        String forgotPasswordLink
) {
}
