package com.anterka.closeauthbackend.common.exception;

public class PasswordReusedException extends RuntimeException{
    public PasswordReusedException(String message){
        super(message);
    }
}
