package com.clouddrop.exception;

// A request that is syntactically fine but breaks a business rule -> HTTP 400.
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
