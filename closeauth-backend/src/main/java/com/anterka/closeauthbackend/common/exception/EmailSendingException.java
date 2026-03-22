package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class EmailSendingException extends CloseAuthException {

    private static final String ERROR_CODE = "EMAIL_SENDING_FAILED";

    public EmailSendingException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}

