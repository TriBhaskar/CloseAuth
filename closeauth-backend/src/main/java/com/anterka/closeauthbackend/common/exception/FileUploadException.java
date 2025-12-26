package com.anterka.closeauthbackend.common.exception;

import org.springframework.http.HttpStatus;

public class FileUploadException extends CloseAuthException {

    private static final String ERROR_CODE = "FILE_UPLOAD_FAILED";

    public FileUploadException(String message) {
        super(message);
    }

    public FileUploadException(String message, Throwable cause) {
        super(message, cause);
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

