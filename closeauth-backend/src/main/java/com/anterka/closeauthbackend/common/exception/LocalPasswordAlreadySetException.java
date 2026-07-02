package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * The user already has a {@code LOCAL_PASSWORD} identity, so a second one cannot be added. Category
 * {@link ErrorCategory#CONFLICT}. (Changing an existing password is {@code changePassword}; setting a
 * new one without the current is the Stage 6 reset path.)
 */
public class LocalPasswordAlreadySetException extends CloseAuthDomainException {

    private static final String CODE = "user.local_password_exists";

    public LocalPasswordAlreadySetException(UUID userId) {
        super(ErrorCategory.CONFLICT, CODE,
                "User already has a local password identity: " + userId,
                Map.of("userId", userId.toString()));
    }
}
