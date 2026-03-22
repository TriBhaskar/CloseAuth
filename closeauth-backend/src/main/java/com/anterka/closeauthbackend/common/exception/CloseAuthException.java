package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception class for all CloseAuth application exceptions.
 * Provides standardized HTTP status and error code for consistent error handling.
 */
public abstract class CloseAuthException extends RuntimeException {

    protected CloseAuthException(String message) {
        super(message);
    }

    protected CloseAuthException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the HTTP status code to be used in the response.
     */
    public abstract HttpStatus getHttpStatus();

    /**
     * Returns a unique error code for client-side error handling.
     */
    public abstract String getErrorCode();
}

