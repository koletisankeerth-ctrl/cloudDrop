package com.clouddrop.repository;

import com.clouddrop.entity.StoredFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    // Always filtered by owner: a user's listing can never include someone else's files.
    Page<StoredFile> findByOwnerIdOrderByUploadedAtDesc(Long ownerId, Pageable pageable);
}
