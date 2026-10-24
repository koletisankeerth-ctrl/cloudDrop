package com.clouddrop.exception;

// "We know who you are, but you may not do this" -> HTTP 403.
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
