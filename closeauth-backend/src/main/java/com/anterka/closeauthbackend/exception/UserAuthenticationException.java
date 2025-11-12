package com.anterka.closeauthbackend.exception;

public class UserAuthenticationException extends RuntimeException{
    public UserAuthenticationException(String message){
        super(message);
    }
}
