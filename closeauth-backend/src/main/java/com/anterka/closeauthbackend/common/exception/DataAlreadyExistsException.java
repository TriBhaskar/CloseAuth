package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class DataAlreadyExistsException extends CloseAuthException {

    private static final String ERROR_CODE = "DATA_ALREADY_EXISTS";

    public DataAlreadyExistsException(String message) {
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
