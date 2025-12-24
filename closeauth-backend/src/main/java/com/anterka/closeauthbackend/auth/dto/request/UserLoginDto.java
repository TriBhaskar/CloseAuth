package com.anterka.closeauthbackend.auth.dto.request;

public record UserLoginDto(
        String email,
        String password
) {
}
