package com.clouddrop.exception;

// Disk/storage failure -> HTTP 500 (details are logged, not returned).
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
