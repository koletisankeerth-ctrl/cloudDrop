package com.clouddrop.dto;

import com.clouddrop.entity.StoredFile;
import java.time.Instant;

// Deliberately omits ownerId and storageKey: clients don't need them and the key is an internal detail.
public record FileResponse(Long id, String originalFilename, String contentType, long sizeBytes, Instant uploadedAt) {

    public static FileResponse from(StoredFile file) {
        return new FileResponse(file.getId(), file.getOriginalFilename(), file.getContentType(),
                file.getSizeBytes(), file.getUploadedAt());
    }
}
