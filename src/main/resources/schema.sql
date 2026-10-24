-- CloudDrop schema (MySQL 8). Idempotent: safe to run on every startup.
-- Hibernate runs with ddl-auto=validate, so these tables must match the JPA entities exactly.

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS files (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id          BIGINT       NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(100) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    uploaded_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_files_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_files_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    INDEX idx_files_owner (owner_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS share_links (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    file_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,   -- hex SHA-256 of the raw token; the raw token is never stored
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_links_token_hash UNIQUE (token_hash),
    -- deleting a file removes its share links
    CONSTRAINT fk_share_links_file FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE CASCADE,
    INDEX idx_share_links_file (file_id)
) ENGINE = InnoDB;
