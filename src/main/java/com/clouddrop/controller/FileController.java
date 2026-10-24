package com.clouddrop.controller;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.dto.FileListResponse;
import com.clouddrop.dto.FileResponse;
import com.clouddrop.exception.BadRequestException;
import com.clouddrop.service.FileService;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    // multipart/form-data with one part named "file". The owner is taken from the JWT, never from the request.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("File is empty");
        }
        Long ownerId = AuthContext.requireUser().id();
        // getInputStream() streams from Tomcat's temp file; the content is never fully loaded into memory.
        try (InputStream in = file.getInputStream()) {
            return FileResponse.from(
                    fileService.upload(ownerId, file.getOriginalFilename(), file.getContentType(), in));
        }
    }

    @GetMapping
    public FileListResponse list(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return FileListResponse.from(fileService.list(AuthContext.requireUser().id(), safePage, safeSize));
    }

    @GetMapping("/{id}")
    public FileResponse metadata(@PathVariable Long id) {
        return FileResponse.from(fileService.getOwned(AuthContext.requireUser().id(), id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id,
                                                        @RequestParam(defaultValue = "false") boolean inline) {
        return FileDownloadResponse.build(fileService.download(AuthContext.requireUser().id(), id), inline);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        fileService.delete(AuthContext.requireUser().id(), id);
    }
}
