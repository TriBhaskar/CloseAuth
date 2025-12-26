package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class ClientOwnershipException extends CloseAuthException {

    private static final String ERROR_CODE = "CLIENT_OWNERSHIP_DENIED";

    public ClientOwnershipException(String message) {
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


