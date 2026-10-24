package com.clouddrop.exception;

// Same message for "unknown email" and "wrong password" so attackers can't discover which emails exist.
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
