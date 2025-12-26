package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class ThemeNotFoundException extends CloseAuthException {

    private static final String ERROR_CODE = "THEME_NOT_FOUND";

    public ThemeNotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}


