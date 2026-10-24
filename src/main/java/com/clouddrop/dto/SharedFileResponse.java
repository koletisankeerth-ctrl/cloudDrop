package com.clouddrop.dto;

import java.time.Instant;

// What an anonymous link holder may see: no file id, no owner, no storage key.
public record SharedFileResponse(String originalFilename, String contentType, long sizeBytes, Instant expiresAt) {
}
