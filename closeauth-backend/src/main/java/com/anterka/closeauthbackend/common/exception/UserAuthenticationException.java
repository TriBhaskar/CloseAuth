package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class UserAuthenticationException extends CloseAuthException {

    private static final String ERROR_CODE = "AUTHENTICATION_FAILED";

    public UserAuthenticationException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.FORBIDDEN;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
