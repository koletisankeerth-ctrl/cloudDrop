package com.clouddrop.dto;

import java.time.Instant;

// "token" is shown ONCE, at creation. It cannot be recovered later because only its hash is stored.
public record ShareLinkResponse(Long shareId, String token, String path, String downloadPath, Instant expiresAt) {
}
