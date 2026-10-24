package com.clouddrop.exception;

// "We don't know who you are" -> HTTP 401.
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
