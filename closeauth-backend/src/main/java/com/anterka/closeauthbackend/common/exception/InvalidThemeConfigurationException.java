package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidThemeConfigurationException extends CloseAuthException {

    private static final String ERROR_CODE = "INVALID_THEME_CONFIG";

    public InvalidThemeConfigurationException(String message) {
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


