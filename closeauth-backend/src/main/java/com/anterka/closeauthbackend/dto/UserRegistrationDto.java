package com.anterka.closeauthbackend.dto;

public record UserRegistrationDto(
        String username,
        String email,
        String password,
        String firstName,
        String lastName,
        String phone
) {}
