package com.anterka.closeauthbackend.exception;

public class WeakPasswordException extends RuntimeException{
    public WeakPasswordException(String message){
        super(message);
    }
}
