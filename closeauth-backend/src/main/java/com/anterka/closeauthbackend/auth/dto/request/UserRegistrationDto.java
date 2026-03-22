package com.anterka.closeauthbackend.auth.dto.request;

public record UserRegistrationDto(
        String username,
        String email,
        String password,
        String firstName,
        String lastName,
        String phone
) {}
