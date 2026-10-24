package com.clouddrop.exception;

// -> HTTP 413
public class FileTooLargeException extends RuntimeException {
    public FileTooLargeException(long maxBytes) {
        super("File exceeds the maximum allowed size of " + maxBytes + " bytes");
    }
}
