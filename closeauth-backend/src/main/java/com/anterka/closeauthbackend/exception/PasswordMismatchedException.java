package com.anterka.closeauthbackend.exception;

public class PasswordMismatchedException extends RuntimeException{
    public PasswordMismatchedException(String message){
        super(message);
    }
}
