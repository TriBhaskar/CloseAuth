package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class PasswordReusedException extends CloseAuthException {

    private static final String ERROR_CODE = "PASSWORD_REUSED";

    public PasswordReusedException(String message) {
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
