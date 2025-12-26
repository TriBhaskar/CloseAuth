package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class RoleAlreadyExistsException extends CloseAuthException {

    private static final String ERROR_CODE = "ROLE_ALREADY_EXISTS";

    public RoleAlreadyExistsException(String message) {
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


