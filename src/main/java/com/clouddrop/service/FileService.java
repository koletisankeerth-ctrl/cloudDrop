package com.clouddrop.service;

import com.clouddrop.entity.StoredFile;
import java.io.InputStream;
import org.springframework.data.domain.Page;

public interface FileService {

    /** Stores the bytes and the metadata. The owner always comes from the validated JWT (via the controller). */
    StoredFile upload(Long ownerId, String originalFilename, String contentType, InputStream content);

    Page<StoredFile> list(Long ownerId, int page, int size);

    /** Ownership-checked download: 404 if missing, 403 if it belongs to someone else. */
    FileContent download(Long ownerId, Long fileId);

    /** Ownership-checked delete of the metadata row (and its share links) and the stored bytes. */
    void delete(Long ownerId, Long fileId);

    /** Returns the file if it exists and belongs to ownerId; throws NotFoundException (404) or ForbiddenException (403). */
    StoredFile getOwned(Long ownerId, Long fileId);
}
