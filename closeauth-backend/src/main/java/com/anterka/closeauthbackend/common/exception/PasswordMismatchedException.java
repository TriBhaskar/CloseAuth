package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class PasswordMismatchedException extends CloseAuthException {

    private static final String ERROR_CODE = "PASSWORD_MISMATCH";

    public PasswordMismatchedException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.BAD_REQUEST;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
