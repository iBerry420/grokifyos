CREATE TABLE IF NOT EXISTS lyre_projects (
    id CHAR(32) NOT NULL PRIMARY KEY,
    user_id INT UNSIGNED NOT NULL,
    name VARCHAR(128) NOT NULL,
    visibility ENUM('private','public') NOT NULL DEFAULT 'private',
    board_id VARCHAR(64) NOT NULL,
    watch_token CHAR(32) NULL,
    is_odysseus TINYINT(1) NOT NULL DEFAULT 0,
    compiled_key VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_lyre_user_board (user_id, board_id),
    UNIQUE KEY uq_lyre_watch_token (watch_token),
    KEY idx_lyre_user_updated (user_id, updated_at),
    CONSTRAINT fk_lyre_projects_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (id) VALUES ('010_lyre_projects');
