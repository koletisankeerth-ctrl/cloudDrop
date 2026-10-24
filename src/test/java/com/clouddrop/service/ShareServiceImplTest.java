package com.clouddrop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clouddrop.entity.ShareLink;
import com.clouddrop.entity.StoredFile;
import com.clouddrop.exception.ForbiddenException;
import com.clouddrop.exception.GoneException;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.repository.ShareLinkRepository;
import com.clouddrop.repository.StoredFileRepository;
import com.clouddrop.service.impl.ShareServiceImpl;
import com.clouddrop.storage.FileStorageService;
import com.clouddrop.util.TokenHasher;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShareServiceImplTest {

    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    ShareLinkRepository shareLinkRepository;
    @Mock
    StoredFileRepository fileRepository;
    @Mock
    FileService fileService;
    @Mock
    FileStorageService storage;

    ShareServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShareServiceImpl(shareLinkRepository, fileRepository, fileService, storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_storesOnlyTheHash_andSetsExpiry() {
        when(fileService.getOwned(1L, 10L)).thenReturn(file());
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatedShare created = service.create(1L, 10L, 60);

        ArgumentCaptor<ShareLink> saved = ArgumentCaptor.forClass(ShareLink.class);
        verify(shareLinkRepository).save(saved.capture());
        ShareLink link = saved.getValue();
        assertThat(created.rawToken()).hasSize(43).matches("[A-Za-z0-9_-]+");     // 256 bits, URL-safe
        assertThat(link.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(created.rawToken()));
        assertThat(link.getTokenHash()).isNotEqualTo(created.rawToken()).hasSize(64);
        assertThat(link.getFileId()).isEqualTo(10L);
        assertThat(link.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(link.getRevokedAt()).isNull();
    }

    @Test
    void create_generatesDifferentTokensEachTime() {
        when(fileService.getOwned(1L, 10L)).thenReturn(file());
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(1L, 10L, 5).rawToken()).isNotEqualTo(service.create(1L, 10L, 5).rawToken());
    }

    @Test
    void create_forSomeoneElsesFile_isForbidden_andNothingIsSaved() {
        when(fileService.getOwned(2L, 10L)).thenThrow(new ForbiddenException("no"));

        assertThatThrownBy(() -> service.create(2L, 10L, 60)).isInstanceOf(ForbiddenException.class);

        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    void resolve_activeLink_returnsFile() {
        StoredFile file = file();
        ShareLink link = link("tok", NOW.plusSeconds(1), null);
        when(shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex("tok"))).thenReturn(Optional.of(link));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));

        ResolvedShare resolved = service.resolve("tok");

        assertThat(resolved.file()).isSameAs(file);
        assertThat(resolved.link()).isSameAs(link);
    }

    @Test
    void resolve_unknownToken_isNotFound() {
        when(shareLinkRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolve_expiredLink_isGone() {
        when(shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex("tok")))
                .thenReturn(Optional.of(link("tok", NOW.minusSeconds(1), null)));

        assertThatThrownBy(() -> service.resolve("tok"))
                .isInstanceOf(GoneException.class).hasMessageContaining("expired");
    }

    @Test
    void resolve_atTheExactExpiryInstant_isAlreadyExpired() {
        when(shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex("tok")))
                .thenReturn(Optional.of(link("tok", NOW, null)));

        assertThatThrownBy(() -> service.resolve("tok")).isInstanceOf(GoneException.class);
    }

    @Test
    void resolve_revokedLink_isGone_evenIfNotExpired() {
        when(shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex("tok")))
                .thenReturn(Optional.of(link("tok", NOW.plus(Duration.ofDays(1)), NOW.minusSeconds(5))));

        assertThatThrownBy(() -> service.resolve("tok"))
                .isInstanceOf(GoneException.class).hasMessageContaining("revoked");
    }

    @Test
    void download_activeLink_opensBytes() {
        StoredFile file = file();
        InputStream bytes = new ByteArrayInputStream(new byte[]{1});
        when(shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex("tok")))
                .thenReturn(Optional.of(link("tok", NOW.plusSeconds(60), null)));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));
        when(storage.open("key-10")).thenReturn(bytes);

        FileContent content = service.download("tok");

        assertThat(content.stream()).isSameAs(bytes);
        assertThat(content.file()).isSameAs(file);
    }

    @Test
    void download_expiredLink_neverOpensBytes() {
        when(shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex("tok")))
                .thenReturn(Optional.of(link("tok", NOW.minusSeconds(1), null)));

        assertThatThrownBy(() -> service.download("tok")).isInstanceOf(GoneException.class);

        verify(storage, never()).open(any());
    }

    private static StoredFile file() {
        StoredFile f = new StoredFile();
        f.setId(10L);
        f.setOwnerId(1L);
        f.setStorageKey("key-10");
        return f;
    }

    private static ShareLink link(String rawToken, Instant expiresAt, Instant revokedAt) {
        ShareLink l = new ShareLink();
        l.setFileId(10L);
        l.setTokenHash(TokenHasher.sha256Hex(rawToken));
        l.setExpiresAt(expiresAt);
        l.setRevokedAt(revokedAt);
        return l;
    }
}
