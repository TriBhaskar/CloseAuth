package com.anterka.closeauthbackend.common.exception;

import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for all CloseAuth exceptions.
 * Uses the CloseAuthException hierarchy to provide consistent error responses.
 */
@ControllerAdvice
@Slf4j
public class GlobalAdviceController {

    /**
     * Handles all CloseAuthException subclasses with a single handler.
     * Each exception provides its own HTTP status and error code.
     */
    @ExceptionHandler(CloseAuthException.class)
    public ResponseEntity<CustomApiResponse<ErrorDetails>> handleCloseAuthException(CloseAuthException ex) {
        log.warn("CloseAuth exception occurred: {} - {}", ex.getErrorCode(), ex.getMessage());

        ErrorDetails errorDetails = new ErrorDetails(ex.getErrorCode(), ex.getMessage());

        return ResponseEntity.status(ex.getHttpStatus())
                .body(CustomApiResponse.error(ex.getMessage(), errorDetails));
    }

    /**
     * Handles validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("Validation failed: {}", errors);

        return ResponseEntity.badRequest()
                .body(CustomApiResponse.error("Validation failed", errors));
    }

    /**
     * Handles access denied exceptions (403 Forbidden).
     * This includes Spring Security's AccessDeniedException and AuthorizationDeniedException.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CustomApiResponse<ErrorDetails>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());

        ErrorDetails errorDetails = new ErrorDetails("ACCESS_DENIED", ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CustomApiResponse.error("Access denied. You do not have permission to perform this action.", errorDetails));
    }

    /**
     * Handles any unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomApiResponse<ErrorDetails>> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred: {}", ex.getMessage(), ex);

        ErrorDetails errorDetails = new ErrorDetails("INTERNAL_ERROR", ex.getMessage());

        return ResponseEntity.internalServerError()
                .body(CustomApiResponse.error("An unexpected error occurred. Please try again later.", errorDetails));
    }

    /**
     * Error details record for structured error responses.
     */
    public record ErrorDetails(String errorCode, String details) {}
}
