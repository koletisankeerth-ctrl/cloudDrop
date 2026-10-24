package com.clouddrop.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.auth.AuthenticatedUser;
import com.clouddrop.entity.StoredFile;
import com.clouddrop.exception.FileTooLargeException;
import com.clouddrop.exception.ForbiddenException;
import com.clouddrop.exception.GlobalExceptionHandler;
import com.clouddrop.exception.NotFoundException;
import com.clouddrop.service.FileContent;
import com.clouddrop.service.FileService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileController.class)
@Import(GlobalExceptionHandler.class)
class FileControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FileService fileService;

    @BeforeEach
    void login() {
        AuthContext.set(new AuthenticatedUser(1L, "alice@example.com"));
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    void upload_validFile_returns201_andUsesOwnerFromToken() throws Exception {
        when(fileService.upload(eq(1L), eq("a.txt"), eq("text/plain"), any())).thenReturn(file(5L));

        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()))
                        .param("ownerId", "999"))   // a client-supplied owner must be ignored
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void upload_emptyFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File is empty"));

        verify(fileService, never()).upload(any(), any(), any(), any());
    }

    @Test
    void upload_missingFilePart_returns400() throws Exception {
        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("wrong-name", "a.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_notMultipart_returns415() throws Exception {
        mockMvc.perform(post("/api/files").contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void upload_tooLarge_returns413() throws Exception {
        when(fileService.upload(any(), any(), any(), any())).thenThrow(new FileTooLargeException(1000));

        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void list_returnsOnlyCallersFiles_withPagination() throws Exception {
        when(fileService.list(1L, 0, 20))
                .thenReturn(new PageImpl<>(List.of(file(5L)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(5))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void list_clampsAbsurdPageSize() throws Exception {
        when(fileService.list(1L, 0, 100)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/api/files").param("size", "100000").param("page", "-4"))
                .andExpect(status().isOk());
    }

    @Test
    void download_defaultsToAttachment_withSafeHeaders() throws Exception {
        when(fileService.download(1L, 5L)).thenReturn(fileContent(file(5L, "application/pdf", "My Report.pdf"), "PDFDATA"));

        mockMvc.perform(get("/api/files/5/download"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("PDFDATA".getBytes()))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Length", "7"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("My%20Report.pdf")));
    }

    @Test
    void download_inline_isAllowedForPdf() throws Exception {
        when(fileService.download(1L, 5L)).thenReturn(fileContent(file(5L, "application/pdf", "a.pdf"), "PDFDATA"));

        mockMvc.perform(get("/api/files/5/download").param("inline", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
    }

    @Test
    void download_inline_isRefusedForHtmlAndSvg() throws Exception {
        for (String type : new String[]{"text/html", "image/svg+xml"}) {
            when(fileService.download(1L, 5L)).thenReturn(fileContent(file(5L, type, "evil"), "<script>"));

            mockMvc.perform(get("/api/files/5/download").param("inline", "true"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        }
    }

    @Test
    void download_someoneElsesFile_returns403() throws Exception {
        when(fileService.download(1L, 5L)).thenThrow(new ForbiddenException("You do not have access to this file"));

        mockMvc.perform(get("/api/files/5/download")).andExpect(status().isForbidden());
    }

    @Test
    void download_missingFile_returns404() throws Exception {
        when(fileService.download(1L, 5L)).thenThrow(new NotFoundException("File not found"));

        mockMvc.perform(get("/api/files/5/download")).andExpect(status().isNotFound());
    }

    @Test
    void delete_ownFile_returns204() throws Exception {
        mockMvc.perform(delete("/api/files/5")).andExpect(status().isNoContent());

        verify(fileService).delete(1L, 5L);
    }

    @Test
    void delete_someoneElsesFile_returns403() throws Exception {
        doThrow(new ForbiddenException("You do not have access to this file")).when(fileService).delete(1L, 5L);

        mockMvc.perform(delete("/api/files/5")).andExpect(status().isForbidden());
    }

    @Test
    void delete_missingFile_returns404() throws Exception {
        doThrow(new NotFoundException("File not found")).when(fileService).delete(1L, 5L);

        mockMvc.perform(delete("/api/files/5")).andExpect(status().isNotFound());
    }

    @Test
    void metadata_ownFile_returns200() throws Exception {
        when(fileService.getOwned(1L, 5L)).thenReturn(file(5L));

        mockMvc.perform(get("/api/files/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("a.txt"));
    }

    @Test
    void metadata_someoneElsesFile_returns403() throws Exception {
        when(fileService.getOwned(1L, 5L)).thenThrow(new ForbiddenException("You do not have access to this file"));

        mockMvc.perform(get("/api/files/5")).andExpect(status().isForbidden());
    }

    @Test
    void metadata_missingFile_returns404() throws Exception {
        when(fileService.getOwned(1L, 5L)).thenThrow(new NotFoundException("File not found"));

        mockMvc.perform(get("/api/files/5")).andExpect(status().isNotFound());
    }

    @Test
    void metadata_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/api/files/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownUrl_returns404_notAn500() throws Exception {
        mockMvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }

    @Test
    void wrongMethod_returns405_notAn500() throws Exception {
        mockMvc.perform(post("/api/files/5")).andExpect(status().isMethodNotAllowed());
    }

    private static FileContent fileContent(StoredFile file, String body) {
        file.setSizeBytes(body.length());
        return new FileContent(file, new java.io.ByteArrayInputStream(body.getBytes()));
    }

    private static StoredFile file(Long id) {
        return file(id, "text/plain", "a.txt");
    }

    private static StoredFile file(Long id, String contentType, String filename) {
        StoredFile f = new StoredFile();
        f.setId(id);
        f.setOwnerId(1L);
        f.setOriginalFilename(filename);
        f.setStorageKey("secret-internal-key");
        f.setContentType(contentType);
        f.setSizeBytes(5);
        f.setUploadedAt(Instant.now());
        return f;
    }
}

