package com.clouddrop.storage;

import com.clouddrop.config.AppProperties;
import com.clouddrop.exception.FileTooLargeException;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.exception.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stores each file as one plain file named after its storage key inside UPLOAD_DIR. */
@Component
public class LocalFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);
    // We only ever generate UUID keys, so anything else (e.g. "../x") is rejected outright.
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path baseDir;

    // Runs at startup: if the directory cannot be created, the application refuses to start.
    public LocalFileStorageService(AppProperties props) {
        try {
            Path dir = Path.of(props.storage().uploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            this.baseDir = dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot use upload directory: " + props.storage().uploadDir(), e);
        }
        log.info("Local file storage directory: {}", baseDir);
    }

    @Override
    public long store(String storageKey, InputStream content, long maxBytes) {
        Path target = resolve(storageKey);
        long total = 0;
        // CREATE_NEW fails if the file already exists, so a collision can never overwrite someone's file.
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];   // fixed memory use, however large the file is
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new FileTooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
            return total;
        } catch (FileTooLargeException e) {
            deleteQuietly(target);
            throw e;
        } catch (IOException e) {
            // Includes a client that disconnects mid-upload, and "file already exists".
            deleteQuietlyIfWeCreatedIt(target, e);
            throw new StorageException("Failed to store file", e);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path path = resolve(storageKey);
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            // A DB row exists but the bytes are gone: a server-side inconsistency worth a loud log line.
            log.error("Metadata exists but file is missing on disk: {}", storageKey);
            throw new NotFoundException("File content not found");
        } catch (IOException e) {
            throw new StorageException("Failed to read file", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new StorageException("Failed to delete file", e);
        }
    }

    // The path-traversal defence: validate the key shape, then double-check the final path is inside baseDir.
    private Path resolve(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = baseDir.resolve(storageKey).normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    private void deleteQuietlyIfWeCreatedIt(Path target, IOException cause) {
        // If the failure was "already exists", the existing file belongs to someone else: leave it alone.
        if (!(cause instanceof java.nio.file.FileAlreadyExistsException)) {
            deleteQuietly(target);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove partial file {}", path, e);
        }
    }
}
