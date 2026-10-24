package com.clouddrop.controller;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.dto.CreateShareRequest;
import com.clouddrop.dto.ShareLinkResponse;
import com.clouddrop.dto.SharedFileResponse;
import com.clouddrop.service.CreatedShare;
import com.clouddrop.service.ResolvedShare;
import com.clouddrop.service.ShareService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShareController {

    private static final int DEFAULT_EXPIRY_MINUTES = 24 * 60;

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    // PRIVATE (needs JWT): only the file's owner can create links. Body is optional.
    @PostMapping("/api/files/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareLinkResponse create(@PathVariable Long id,
                                    @Valid @RequestBody(required = false) CreateShareRequest request) {
        int minutes = (request == null || request.expiresInMinutes() == null)
                ? DEFAULT_EXPIRY_MINUTES : request.expiresInMinutes();
        CreatedShare created = shareService.create(AuthContext.requireUser().id(), id, minutes);
        String path = "/api/shares/" + created.rawToken();
        return new ShareLinkResponse(created.link().getId(), created.rawToken(), path, path + "/download",
                created.link().getExpiresAt());
    }

    // PUBLIC (no JWT, see JwtAuthFilter): the unguessable token IS the credential.
    @GetMapping("/api/shares/{token}")
    public SharedFileResponse info(@PathVariable String token) {
        ResolvedShare share = shareService.resolve(token);
        return new SharedFileResponse(share.file().getOriginalFilename(), share.file().getContentType(),
                share.file().getSizeBytes(), share.link().getExpiresAt());
    }

    // PUBLIC: streams the shared file. An addition to the original endpoint list, because a share link
    // that cannot deliver the file would not be much use.
    @GetMapping("/api/shares/{token}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String token,
                                                        @RequestParam(defaultValue = "false") boolean inline) {
        return FileDownloadResponse.build(shareService.download(token), inline);
    }
}
