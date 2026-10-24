package com.clouddrop.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clouddrop.config.AppProperties;
import com.clouddrop.exception.FileTooLargeException;
import com.clouddrop.exception.StorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    LocalFileStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorageService(
                new AppProperties(new AppProperties.Jwt("x".repeat(32), 24),
                        new AppProperties.Storage(tempDir.resolve("uploads").toString(), 1000)));
    }

    @Test
    void store_writesBytesAndReturnsSize() throws IOException {
        String key = UUID.randomUUID().toString();

        long size = storage.store(key, stream("hello world"), 1000);

        assertThat(size).isEqualTo(11);
        assertThat(Files.readString(tempDir.resolve("uploads").resolve(key))).isEqualTo("hello world");
    }

    @Test
    void store_tooLarge_throwsAndLeavesNoFile() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> storage.store(key, stream("x".repeat(1001)), 1000))
                .isInstanceOf(FileTooLargeException.class);

        assertThat(tempDir.resolve("uploads").resolve(key)).doesNotExist();
    }

    @Test
    void store_streamFailsMidway_leavesNoFile() {
        String key = UUID.randomUUID().toString();
        InputStream broken = new InputStream() {
            int calls = 0;

            @Override
            public int read() throws IOException {
                throw new IOException("client disconnected");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (calls++ == 0) {
                    b[off] = 1;
                    return 1;
                }
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> storage.store(key, broken, 1000)).isInstanceOf(StorageException.class);

        assertThat(tempDir.resolve("uploads").resolve(key)).doesNotExist();
    }

    @Test
    void store_sameKeyTwice_doesNotOverwriteExistingFile() throws IOException {
        String key = UUID.randomUUID().toString();
        storage.store(key, stream("original"), 1000);

        assertThatThrownBy(() -> storage.store(key, stream("replacement"), 1000))
                .isInstanceOf(StorageException.class);

        assertThat(Files.readString(tempDir.resolve("uploads").resolve(key))).isEqualTo("original");
    }

    @Test
    void pathTraversalKeys_areRejected() {
        for (String bad : new String[]{"../evil", "..\\evil", "/etc/passwd", "a/b", "not-a-uuid", "", null}) {
            assertThatThrownBy(() -> storage.store(bad, stream("x"), 1000))
                    .as("key: " + bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(tempDir.resolve("evil")).doesNotExist();
    }

    @Test
    void open_returnsStoredBytes() throws IOException {
        String key = UUID.randomUUID().toString();
        storage.store(key, stream("pdf-bytes"), 1000);

        try (InputStream in = storage.open(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("pdf-bytes");
        }
    }

    @Test
    void open_missingFile_throwsNotFound() {
        assertThatThrownBy(() -> storage.open(UUID.randomUUID().toString()))
                .isInstanceOf(com.clouddrop.exception.NotFoundException.class);
    }

    @Test
    void open_traversalKey_isRejected() {
        assertThatThrownBy(() -> storage.open("../../secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_removesFile_andIsIdempotent() {
        String key = UUID.randomUUID().toString();
        storage.store(key, stream("data"), 1000);

        storage.delete(key);
        storage.delete(key);   // second call must not fail

        assertThat(tempDir.resolve("uploads").resolve(key)).doesNotExist();
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
