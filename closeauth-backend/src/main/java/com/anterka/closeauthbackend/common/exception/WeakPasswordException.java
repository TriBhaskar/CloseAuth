package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class WeakPasswordException extends CloseAuthException {

    private static final String ERROR_CODE = "WEAK_PASSWORD";

    public WeakPasswordException(String message) {
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
