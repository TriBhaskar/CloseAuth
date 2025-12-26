package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class CredentialValidationException extends CloseAuthException {

    private static final String ERROR_CODE = "CREDENTIAL_VALIDATION_FAILED";

    public CredentialValidationException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNAUTHORIZED;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
