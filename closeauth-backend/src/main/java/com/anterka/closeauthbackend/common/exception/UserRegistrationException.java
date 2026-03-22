package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class UserRegistrationException extends CloseAuthException {

    private static final String ERROR_CODE = "REGISTRATION_FAILED";

    public UserRegistrationException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.CONFLICT;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
