package com.clouddrop.exception;

// Existed once but is no longer available (expired or revoked share link) -> HTTP 410.
public class GoneException extends RuntimeException {
    public GoneException(String message) {
        super(message);
    }
}
