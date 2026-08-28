-- Private likes / follows for the Discord media pane (GrokifyOS only).
-- Also pins AI jobs to a single Discord message snowflake (discogram tag/analyze).

CREATE TABLE IF NOT EXISTS discord_media_likes (
    user_id INT UNSIGNED NOT NULL,
    attachment_id INT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, attachment_id),
    KEY idx_discord_media_likes_att (attachment_id),
    CONSTRAINT fk_discord_media_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS discord_media_follows (
    user_id INT UNSIGNED NOT NULL,
    discord_user_id VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, discord_user_id),
    KEY idx_discord_media_follows_target (discord_user_id),
    CONSTRAINT fk_discord_media_follows_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE discord_ai_jobs
    ADD COLUMN discord_message_id VARCHAR(32) NOT NULL DEFAULT '' AFTER discord_user_id;

INSERT IGNORE INTO schema_migrations (id) VALUES ('008_discord_media');
