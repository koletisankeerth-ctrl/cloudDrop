package com.clouddrop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clouddrop.config.AppProperties;
import com.clouddrop.entity.StoredFile;
import com.clouddrop.exception.BadRequestException;
import com.clouddrop.exception.FileTooLargeException;
import com.clouddrop.exception.ForbiddenException;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.exception.StorageException;
import org.mockito.InOrder;
import com.clouddrop.exception.UnauthorizedException;
import com.clouddrop.repository.StoredFileRepository;
import com.clouddrop.repository.UserRepository;
import com.clouddrop.service.impl.FileServiceImpl;
import com.clouddrop.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    StoredFileRepository fileRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    FileStorageService storage;

    FileServiceImpl service;
    InputStream content = new ByteArrayInputStream(new byte[]{1, 2, 3});

    @BeforeEach
    void setUp() {
        service = new FileServiceImpl(fileRepository, userRepository, storage,
                new AppProperties(new AppProperties.Jwt("x".repeat(32), 24),
                        new AppProperties.Storage("unused", 1000)));
    }

    @Test
    void upload_storesBytesThenSavesMetadata() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(storage.store(anyString(), eq(content), eq(1000L))).thenReturn(3L);
        when(fileRepository.save(any(StoredFile.class))).thenAnswer(inv -> inv.getArgument(0));

        StoredFile saved = service.upload(1L, "../../sneaky.txt", "text/plain", content);

        assertThat(saved.getOwnerId()).isEqualTo(1L);
        assertThat(saved.getOriginalFilename()).isEqualTo("sneaky.txt");
        assertThat(saved.getSizeBytes()).isEqualTo(3);
        assertThat(saved.getContentType()).isEqualTo("text/plain");
        // key is a server-generated UUID, unrelated to the client filename
        assertThat(saved.getStorageKey()).matches("[0-9a-f-]{36}");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void upload_invalidContentType_fallsBackToOctetStream() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(storage.store(anyString(), any(), anyLong())).thenReturn(3L);
        when(fileRepository.save(any(StoredFile.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.upload(1L, "a.bin", "garbage///", content).getContentType())
                .isEqualTo("application/octet-stream");
    }

    @Test
    void upload_databaseFailure_deletesTheStoredFile() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(storage.store(anyString(), any(), anyLong())).thenReturn(3L);
        when(fileRepository.save(any(StoredFile.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.upload(1L, "a.txt", "text/plain", content))
                .hasMessage("db down");

        ArgumentCaptor<String> storedKey = ArgumentCaptor.forClass(String.class);
        verify(storage).store(storedKey.capture(), any(), anyLong());
        verify(storage).delete(storedKey.getValue());   // no orphan left behind
    }

    @Test
    void upload_emptyContent_isRejectedAndFileRemoved() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(storage.store(anyString(), any(), anyLong())).thenReturn(0L);

        assertThatThrownBy(() -> service.upload(1L, "a.txt", "text/plain", content))
                .isInstanceOf(BadRequestException.class);

        verify(storage).delete(anyString());
        verify(fileRepository, never()).save(any());
    }

    @Test
    void upload_tooLarge_savesNothing() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(storage.store(anyString(), any(), anyLong())).thenThrow(new FileTooLargeException(1000));

        assertThatThrownBy(() -> service.upload(1L, "a.txt", "text/plain", content))
                .isInstanceOf(FileTooLargeException.class);

        verify(fileRepository, never()).save(any());
    }

    @Test
    void upload_unknownUser_isRejectedBeforeAnythingIsStored() {
        when(userRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.upload(9L, "a.txt", "text/plain", content))
                .isInstanceOf(UnauthorizedException.class);

        verify(storage, never()).store(anyString(), any(), anyLong());
    }

    @Test
    void getOwned_ownFile_isReturned() {
        StoredFile file = fileOwnedBy(1L);
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));

        assertThat(service.getOwned(1L, 10L)).isSameAs(file);
    }

    @Test
    void getOwned_someoneElsesFile_isForbidden() {
        when(fileRepository.findById(10L)).thenReturn(Optional.of(fileOwnedBy(2L)));

        assertThatThrownBy(() -> service.getOwned(1L, 10L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getOwned_missingFile_isNotFound() {
        when(fileRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwned(1L, 10L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void download_ownFile_opensStoredBytes() {
        StoredFile file = fileOwnedBy(1L);
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));
        when(storage.open("key-10")).thenReturn(content);

        FileContent result = service.download(1L, 10L);

        assertThat(result.file()).isSameAs(file);
        assertThat(result.stream()).isSameAs(content);
    }

    @Test
    void download_someoneElsesFile_isForbidden_andBytesAreNeverOpened() {
        when(fileRepository.findById(10L)).thenReturn(Optional.of(fileOwnedBy(2L)));

        assertThatThrownBy(() -> service.download(1L, 10L)).isInstanceOf(ForbiddenException.class);

        verify(storage, never()).open(anyString());
    }

    @Test
    void delete_ownFile_removesRowThenBytes() {
        StoredFile file = fileOwnedBy(1L);
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));

        service.delete(1L, 10L);

        InOrder order = inOrder(fileRepository, storage);
        order.verify(fileRepository).delete(file);
        order.verify(storage).delete("key-10");
    }

    @Test
    void delete_someoneElsesFile_isForbidden_andNothingIsDeleted() {
        when(fileRepository.findById(10L)).thenReturn(Optional.of(fileOwnedBy(2L)));

        assertThatThrownBy(() -> service.delete(1L, 10L)).isInstanceOf(ForbiddenException.class);

        verify(fileRepository, never()).delete(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void delete_missingFile_isNotFound() {
        when(fileRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1L, 10L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_whenDiskDeleteFails_stillSucceeds() {
        when(fileRepository.findById(10L)).thenReturn(Optional.of(fileOwnedBy(1L)));
        doThrow(new StorageException("disk", new java.io.IOException())).when(storage).delete("key-10");

        service.delete(1L, 10L);   // must not throw: the row is already gone, leftover bytes are only logged

        verify(fileRepository).delete(any(StoredFile.class));
    }

    private StoredFile fileOwnedBy(Long ownerId) {
        StoredFile f = new StoredFile();
        f.setStorageKey("key-10");
        f.setId(10L);
        f.setOwnerId(ownerId);
        return f;
    }
}
