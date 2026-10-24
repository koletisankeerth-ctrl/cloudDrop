package com.clouddrop.controller;

import com.clouddrop.entity.StoredFile;
import com.clouddrop.service.FileContent;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds the HTTP response that streams a file. Shared by the owner download and the public share download,
 * so both get identical safety headers.
 * Default is a forced download. "inline" shows the file in the browser, but ONLY for a short allow-list of
 * types that cannot run scripts: the content type is client-supplied, so serving e.g. text/html or
 * image/svg+xml inline would let an uploader run script in a viewer's browser.
 */
final class FileDownloadResponse {

    private static final Set<String> INLINE_SAFE_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "text/plain");

    private FileDownloadResponse() {
    }

    static ResponseEntity<InputStreamResource> build(FileContent content, boolean inline) {
        StoredFile file = content.file();
        MediaType mediaType = MediaType.parseMediaType(file.getContentType());
        boolean showInline = inline && INLINE_SAFE_TYPES.contains(mediaType.getType() + "/" + mediaType.getSubtype());
        ContentDisposition disposition = (showInline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.getOriginalFilename(), StandardCharsets.UTF_8)   // RFC 5987 encoding, header-safe
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")   // browser must trust our Content-Type, no guessing
                .cacheControl(CacheControl.noStore())          // private files must not be cached
                .body(new InputStreamResource(content.stream()));
    }
}
