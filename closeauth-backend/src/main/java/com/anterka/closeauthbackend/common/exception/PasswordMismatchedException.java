package com.anterka.closeauthbackend.common.exception;

public class PasswordMismatchedException extends RuntimeException{
    public PasswordMismatchedException(String message){
        super(message);
    }
}
