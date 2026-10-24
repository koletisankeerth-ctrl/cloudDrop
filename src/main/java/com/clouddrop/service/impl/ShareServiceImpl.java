package com.clouddrop.service.impl;

import com.clouddrop.entity.ShareLink;
import com.clouddrop.entity.StoredFile;
import com.clouddrop.exception.GoneException;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.repository.ShareLinkRepository;
import com.clouddrop.repository.StoredFileRepository;
import com.clouddrop.service.CreatedShare;
import com.clouddrop.service.FileContent;
import com.clouddrop.service.FileService;
import com.clouddrop.service.ResolvedShare;
import com.clouddrop.service.ShareService;
import com.clouddrop.storage.FileStorageService;
import com.clouddrop.util.TokenHasher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class ShareServiceImpl implements ShareService {

    private static final int TOKEN_BYTES = 32;   // 256 bits of randomness
    // SecureRandom = cryptographically strong. Never use java.util.Random for tokens: it is predictable.
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository shareLinkRepository;
    private final StoredFileRepository fileRepository;
    private final FileService fileService;
    private final FileStorageService storage;
    private final Clock clock;

    public ShareServiceImpl(ShareLinkRepository shareLinkRepository, StoredFileRepository fileRepository,
                            FileService fileService, FileStorageService storage, Clock clock) {
        this.shareLinkRepository = shareLinkRepository;
        this.fileRepository = fileRepository;
        this.fileService = fileService;
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public CreatedShare create(Long ownerId, Long fileId, int expiresInMinutes) {
        fileService.getOwned(ownerId, fileId);   // throws 404/403 unless the caller owns this file

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe Base64 without padding: 43 characters that need no escaping in a URL path
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        ShareLink link = new ShareLink();
        link.setFileId(fileId);
        link.setTokenHash(TokenHasher.sha256Hex(rawToken));   // only the hash goes to the database
        link.setCreatedAt(now);
        link.setExpiresAt(now.plus(Duration.ofMinutes(expiresInMinutes)));
        return new CreatedShare(shareLinkRepository.save(link), rawToken);
    }

    @Override
    public ResolvedShare resolve(String rawToken) {
        ShareLink link = shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> new NotFoundException("Share link not found"));

        if (link.getRevokedAt() != null) {
            throw new GoneException("Share link has been revoked");
        }
        // "not before" means: at the exact expiry instant the link is already dead
        if (!clock.instant().isBefore(link.getExpiresAt())) {
            throw new GoneException("Share link has expired");
        }
        StoredFile file = fileRepository.findById(link.getFileId())
                .orElseThrow(() -> new NotFoundException("Share link not found"));
        return new ResolvedShare(link, file);
    }

    @Override
    public FileContent download(String rawToken) {
        StoredFile file = resolve(rawToken).file();
        return new FileContent(file, storage.open(file.getStorageKey()));
    }
}
