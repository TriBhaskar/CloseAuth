package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a client exceeds an allowed rate limit for an action.
 * Mapped to HTTP 429 (Too Many Requests) by {@code GlobalAdviceController}.
 */
public class RateLimitExceededException extends CloseAuthException {

    private static final String ERROR_CODE = "RATE_LIMIT_EXCEEDED";

    public RateLimitExceededException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}

