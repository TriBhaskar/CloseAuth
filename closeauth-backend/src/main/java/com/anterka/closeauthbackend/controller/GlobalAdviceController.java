package com.anterka.closeauthbackend.controller;

import com.anterka.closeauthbackend.exception.UserRegistrationException;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;

@ControllerAdvice
public class GlobalAdviceController extends RuntimeException {
    @Data
    @Builder
    @RequiredArgsConstructor
    public static class ErrorResponse {
        private final String message;
        private final HttpStatus status;
        private final LocalDateTime timestamp = LocalDateTime.now();
    }

    @ExceptionHandler(UserRegistrationException.class)
    public ResponseEntity<ErrorResponse> handleEnterpriseRegException(UserRegistrationException ex){
        return new ResponseEntity<>(new ErrorResponse(ex.getMessage(), HttpStatus.CONFLICT), HttpStatus.CONFLICT);
    }
}
