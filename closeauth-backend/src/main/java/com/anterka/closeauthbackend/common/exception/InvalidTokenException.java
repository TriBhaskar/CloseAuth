package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidTokenException extends CloseAuthException {

    private static final String ERROR_CODE = "INVALID_TOKEN";

    public InvalidTokenException(String message) {
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
