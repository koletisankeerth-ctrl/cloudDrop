package com.clouddrop.service.impl;

import com.clouddrop.config.AppProperties;
import com.clouddrop.entity.StoredFile;
import com.clouddrop.exception.BadRequestException;
import com.clouddrop.exception.ForbiddenException;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.exception.UnauthorizedException;
import com.clouddrop.repository.StoredFileRepository;
import com.clouddrop.repository.UserRepository;
import com.clouddrop.service.FileContent;
import com.clouddrop.service.FileService;
import com.clouddrop.storage.FileStorageService;
import com.clouddrop.util.FilenameSanitizer;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StoredFileRepository fileRepository;
    private final UserRepository userRepository;
    private final FileStorageService storage;
    private final long maxFileSizeBytes;

    public FileServiceImpl(StoredFileRepository fileRepository, UserRepository userRepository,
                           FileStorageService storage, AppProperties props) {
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.maxFileSizeBytes = props.storage().maxFileSizeBytes();
    }

    /**
     * Deliberately NOT @Transactional: a transaction would hold a database connection open for the whole
     * (possibly huge) upload. Order of steps = how partial failures are handled:
     *   1. write bytes to disk        (failure -> storage cleans up its partial file, nothing in DB)
     *   2. save metadata row to MySQL (failure -> we delete the file we just wrote, then rethrow)
     * The only leftover case is a crash/kill between steps 1 and 2: an orphan file with no DB row.
     * It is harmless (unreachable, key is an unguessable UUID) and can be found by comparing disk to DB.
     */
    @Override
    public StoredFile upload(Long ownerId, String originalFilename, String contentType, InputStream content) {
        // A token can outlive its account; fail early with 401 instead of a foreign-key error after the upload.
        if (!userRepository.existsById(ownerId)) {
            throw new UnauthorizedException("Account no longer exists");
        }

        String storageKey = UUID.randomUUID().toString();   // server-controlled, never derived from the client
        long size = storage.store(storageKey, content, maxFileSizeBytes);
        try {
            if (size == 0) {
                throw new BadRequestException("File is empty");
            }
            StoredFile file = new StoredFile();
            file.setOwnerId(ownerId);
            file.setOriginalFilename(FilenameSanitizer.sanitize(originalFilename));
            file.setStorageKey(storageKey);
            file.setContentType(normalizeContentType(contentType));
            file.setSizeBytes(size);
            file.setUploadedAt(Instant.now());
            return fileRepository.save(file);
        } catch (RuntimeException e) {
            removeOrphan(storageKey);
            throw e;
        }
    }

    @Override
    public Page<StoredFile> list(Long ownerId, int page, int size) {
        return fileRepository.findByOwnerIdOrderByUploadedAtDesc(ownerId, PageRequest.of(page, size));
    }

    @Override
    public StoredFile getOwned(Long ownerId, Long fileId) {
        StoredFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("File not found"));
        if (!file.getOwnerId().equals(ownerId)) {
            throw new ForbiddenException("You do not have access to this file");
        }
        return file;
    }

    @Override
    public FileContent download(Long ownerId, Long fileId) {
        StoredFile file = getOwned(ownerId, fileId);   // ownership is checked BEFORE any bytes are opened
        return new FileContent(file, storage.open(file.getStorageKey()));
    }

    /**
     * Database row first, bytes second. If the second step fails we only have an unreachable leftover file
     * (logged), which is better than the reverse: a DB row pointing at bytes that no longer exist.
     * Share links are removed by the ON DELETE CASCADE foreign key.
     */
    @Override
    public void delete(Long ownerId, Long fileId) {
        StoredFile file = getOwned(ownerId, fileId);
        fileRepository.delete(file);
        removeOrphan(file.getStorageKey());
    }

    private void removeOrphan(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            log.error("Orphan file left on disk, needs manual cleanup: {}", storageKey, cleanupFailure);
        }
    }

    // The content type is client-supplied and untrusted. We keep it only if it parses as a media type.
    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.length() > 255) {
            return DEFAULT_CONTENT_TYPE;
        }
        try {
            return MediaType.parseMediaType(contentType.trim()).toString();
        } catch (RuntimeException e) {
            return DEFAULT_CONTENT_TYPE;
        }
    }
}
