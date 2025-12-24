package com.anterka.closeauthbackend.auth.dto.response;

import java.time.LocalDateTime;

public record UserLoginResponse(
        Integer userId,
        String email,
        String firstName,
        String lastName,
        String accessToken,
        LocalDateTime tokenExpiresAt
) {
    public static UserLoginResponse success(
            Integer userId,
            String email,
            String firstName,
            String lastName,
            String accessToken,
            LocalDateTime tokenExpiresAt) {
        return new UserLoginResponse(
                userId,
                email,
                firstName,
                lastName,
                accessToken,
                tokenExpiresAt
        );
    }
}

