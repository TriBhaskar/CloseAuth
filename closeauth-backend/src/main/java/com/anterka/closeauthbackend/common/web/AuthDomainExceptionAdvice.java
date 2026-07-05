package com.anterka.closeauthbackend.common.web;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>Stage 6b-i stopgap</b> HTTP mapping for {@link CloseAuthDomainException}, so the auth-flow endpoints
 * (registration, verification, magic-link, reset) return a sensible status + body instead of a 500. Maps the coarse
 * {@link ErrorCategory} to an HTTP status (the indicative mapping documented on {@code ErrorCategory}).
 *
 * <p>Stage 7 owns the single, canonical category→status→RFC-7807 mapping for the whole platform and will supersede
 * this. Enumeration-sensitive outcomes (invalid reset/verify token, account existence) are NOT surfaced as exceptions
 * by the flows — they return uniform result objects — so this advice only ever renders safe-to-reveal conditions
 * (e.g. duplicate-email conflict, validation errors).
 */
@RestControllerAdvice
public class AuthDomainExceptionAdvice {

    @ExceptionHandler(CloseAuthDomainException.class)
    public ResponseEntity<Map<String, Object>> handle(CloseAuthDomainException ex) {
        HttpStatus status = switch (ex.getCategory()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, STATE -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case VALIDATION -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
