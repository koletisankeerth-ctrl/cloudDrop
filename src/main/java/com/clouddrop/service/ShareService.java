package com.clouddrop.service;

public interface ShareService {

    /** Owner-only: creates a link that works for the given number of minutes. 403/404 if not the caller's file. */
    CreatedShare create(Long ownerId, Long fileId, int expiresInMinutes);

    /** Public: finds the share by raw token. 404 if unknown, 410 if revoked or expired. */
    ResolvedShare resolve(String rawToken);

    /** Public: same checks as resolve, then opens the bytes. The caller must close the stream. */
    FileContent download(String rawToken);
}
