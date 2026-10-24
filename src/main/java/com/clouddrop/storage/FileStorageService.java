package com.clouddrop.storage;

import java.io.InputStream;

/**
 * Where file BYTES live. Business code (FileService) only knows this interface, so an S3
 * implementation can replace LocalFileStorageService later without touching business logic.
 * Metadata (name, owner, size) lives in MySQL, not here. Implementations throw unchecked exceptions
 * (StorageException, FileTooLargeException) so no java.io details leak into callers.
 */
public interface FileStorageService {

    /**
     * Streams the content to storage under the given server-generated key and returns the number of
     * bytes written. Throws FileTooLargeException if it exceeds maxBytes. On ANY failure nothing is left behind.
     */
    long store(String storageKey, InputStream content, long maxBytes);

    /** Opens the stored bytes for streaming. The CALLER must close the stream. Throws NotFoundException if missing. */
    InputStream open(String storageKey);

    /** Deletes the stored bytes. Does nothing if the key does not exist. */
    void delete(String storageKey);
}
