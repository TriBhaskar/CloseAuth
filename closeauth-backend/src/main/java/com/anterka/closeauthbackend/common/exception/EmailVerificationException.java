package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class EmailVerificationException extends CloseAuthException {

    private static final String ERROR_CODE = "EMAIL_VERIFICATION_FAILED";

    public EmailVerificationException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.EXPECTATION_FAILED;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
