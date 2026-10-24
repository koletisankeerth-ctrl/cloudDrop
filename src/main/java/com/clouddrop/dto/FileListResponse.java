package com.clouddrop.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record FileListResponse(List<FileResponse> items, int page, int size, long totalElements, int totalPages) {

    public static FileListResponse from(Page<com.clouddrop.entity.StoredFile> page) {
        return new FileListResponse(page.getContent().stream().map(FileResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
