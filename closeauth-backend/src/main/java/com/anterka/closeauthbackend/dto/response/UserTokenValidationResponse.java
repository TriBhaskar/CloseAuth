package com.anterka.closeauthbackend.dto.response;

public record UserTokenValidationResponse(
        boolean valid,
        String message
) {
}
