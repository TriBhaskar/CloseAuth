package com.anterka.closeauthbackend.dto.request;

public record UserLoginDto(
        String email,
        String password
) {
}
