package com.anterka.closeauthbackend.auth.dto.response;

public record UserTokenValidationResponse(
        boolean valid,
        String message
) {
}
