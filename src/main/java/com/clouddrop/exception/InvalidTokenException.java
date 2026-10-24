package com.clouddrop.exception;

// Thrown for any bad JWT. The message is for server logs only and is never sent to the client.
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
