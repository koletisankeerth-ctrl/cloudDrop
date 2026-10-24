package com.clouddrop.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.auth.AuthenticatedUser;
import com.clouddrop.entity.ShareLink;
import com.clouddrop.entity.StoredFile;
import com.clouddrop.exception.ForbiddenException;
import com.clouddrop.exception.GlobalExceptionHandler;
import com.clouddrop.exception.GoneException;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.service.CreatedShare;
import com.clouddrop.service.FileContent;
import com.clouddrop.service.ResolvedShare;
import com.clouddrop.service.ShareService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShareController.class)
@Import(GlobalExceptionHandler.class)
class ShareControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ShareService shareService;

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    void create_withBody_usesRequestedMinutes_andReturnsRawTokenOnce() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "a@b.com"));
        when(shareService.create(1L, 10L, 30)).thenReturn(created());

        mockMvc.perform(post("/api/files/10/shares").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInMinutes\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("raw-token"))
                .andExpect(jsonPath("$.path").value("/api/shares/raw-token"))
                .andExpect(jsonPath("$.downloadPath").value("/api/shares/raw-token/download"))
                .andExpect(jsonPath("$.tokenHash").doesNotExist());
    }

    @Test
    void create_withoutBody_defaultsTo24Hours() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "a@b.com"));
        when(shareService.create(1L, 10L, 1440)).thenReturn(created());

        mockMvc.perform(post("/api/files/10/shares")).andExpect(status().isCreated());
    }

    @Test
    void create_invalidExpiry_returns400() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "a@b.com"));

        for (String body : new String[]{"{\"expiresInMinutes\":0}", "{\"expiresInMinutes\":-5}",
                "{\"expiresInMinutes\":999999}"}) {
            mockMvc.perform(post("/api/files/10/shares").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.expiresInMinutes").exists());
        }
        verify(shareService, never()).create(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void create_forSomeoneElsesFile_returns403() throws Exception {
        AuthContext.set(new AuthenticatedUser(1L, "a@b.com"));
        when(shareService.create(eq(1L), eq(10L), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new ForbiddenException("no"));

        mockMvc.perform(post("/api/files/10/shares")).andExpect(status().isForbidden());
    }

    @Test
    void info_activeLink_returnsOnlySafeFields() throws Exception {
        when(shareService.resolve("raw-token")).thenReturn(new ResolvedShare(link(), file("application/pdf")));

        mockMvc.perform(get("/api/shares/raw-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("a.pdf"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist());
    }

    @Test
    void info_expiredOrRevoked_returns410() throws Exception {
        when(shareService.resolve("old")).thenThrow(new GoneException("Share link has expired"));

        mockMvc.perform(get("/api/shares/old"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Share link has expired"));
    }

    @Test
    void info_unknownToken_returns404() throws Exception {
        when(shareService.resolve("nope")).thenThrow(new NotFoundException("Share link not found"));

        mockMvc.perform(get("/api/shares/nope")).andExpect(status().isNotFound());
    }

    @Test
    void download_streamsFile_withSafeHeaders_andNoLogin() throws Exception {
        when(shareService.download("raw-token")).thenReturn(
                new FileContent(file("application/pdf"), new ByteArrayInputStream("PDF".getBytes())));

        mockMvc.perform(get("/api/shares/raw-token/download").param("inline", "true"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("PDF".getBytes()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
    }

    @Test
    void download_expired_returns410() throws Exception {
        when(shareService.download("old")).thenThrow(new GoneException("Share link has expired"));

        mockMvc.perform(get("/api/shares/old/download")).andExpect(status().isGone());
    }

    private static CreatedShare created() {
        ShareLink link = new ShareLink();
        link.setId(7L);
        link.setExpiresAt(Instant.parse("2026-01-01T13:00:00Z"));
        return new CreatedShare(link, "raw-token");
    }

    private static ShareLink link() {
        ShareLink link = new ShareLink();
        link.setExpiresAt(Instant.parse("2026-01-01T13:00:00Z"));
        return link;
    }

    private static StoredFile file(String contentType) {
        StoredFile f = new StoredFile();
        f.setId(10L);
        f.setOwnerId(1L);
        f.setStorageKey("secret-key");
        f.setOriginalFilename("a.pdf");
        f.setContentType(contentType);
        f.setSizeBytes(3);
        return f;
    }
}
