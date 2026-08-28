-- Persistent Discogram mix: ordered attachment ids + resume cursor per operator/guild.

CREATE TABLE IF NOT EXISTS discord_media_playlists (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id INT UNSIGNED NOT NULL,
    guild_id VARCHAR(32) NOT NULL DEFAULT '',
    cursor_index INT UNSIGNED NOT NULL DEFAULT 0,
    cursor_attachment_id INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_discord_media_playlist_user_guild (user_id, guild_id),
    CONSTRAINT fk_discord_media_playlists_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS discord_media_playlist_items (
    playlist_id INT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    attachment_id INT UNSIGNED NOT NULL,
    PRIMARY KEY (playlist_id, position),
    UNIQUE KEY uq_discord_media_playlist_att (playlist_id, attachment_id),
    CONSTRAINT fk_discord_media_playlist_items FOREIGN KEY (playlist_id) REFERENCES discord_media_playlists (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (id) VALUES ('009_discord_media_playlist');
