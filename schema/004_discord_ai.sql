-- Discord inner-app AI analysis jobs (semantic tagging)
-- Message.tags still live in Avalynn. Jobs and results are tracked here.

CREATE TABLE IF NOT EXISTS discord_ai_jobs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id INT NOT NULL DEFAULT 0,
    scope VARCHAR(16) NOT NULL DEFAULT 'all',
    guild_id VARCHAR(32) NOT NULL DEFAULT '',
    channel_id VARCHAR(32) NOT NULL DEFAULT '',
    user_id INT NOT NULL DEFAULT 0,
    discord_user_id VARCHAR(32) NOT NULL DEFAULT '',
    timeframe VARCHAR(32) NOT NULL DEFAULT '1d',
    from_date VARCHAR(32) NOT NULL DEFAULT '',
    to_date VARCHAR(32) NOT NULL DEFAULT '',
    message_limit INT NOT NULL DEFAULT 50,
    skip_tagged TINYINT(1) NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'queued',
    total INT NOT NULL DEFAULT 0,
    processed INT NOT NULL DEFAULT 0,
    tagged INT NOT NULL DEFAULT 0,
    skipped INT NOT NULL DEFAULT 0,
    failed INT NOT NULL DEFAULT 0,
    last_message_id INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512) NOT NULL DEFAULT '',
    label VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_discord_ai_jobs_status_time (status, created_at),
    KEY idx_discord_ai_jobs_bot (bot_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS discord_ai_results (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT UNSIGNED NOT NULL,
    message_id INT NOT NULL,
    discord_message_id VARCHAR(32) NOT NULL DEFAULT '',
    bot_id INT NOT NULL DEFAULT 0,
    guild_id VARCHAR(32) NOT NULL DEFAULT '',
    guild_name VARCHAR(128) NOT NULL DEFAULT '',
    channel_id VARCHAR(32) NOT NULL DEFAULT '',
    channel_name VARCHAR(128) NOT NULL DEFAULT '',
    user_id INT NOT NULL DEFAULT 0,
    discord_user_id VARCHAR(32) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    avatar VARCHAR(512) NOT NULL DEFAULT '',
    content TEXT NULL,
    tags VARCHAR(2048) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ok',
    error VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_discord_ai_results_job (job_id, id),
    KEY idx_discord_ai_results_created (created_at),
    KEY idx_discord_ai_results_guild (guild_id, created_at),
    KEY idx_discord_ai_results_user (user_id, created_at),
    CONSTRAINT fk_discord_ai_results_job FOREIGN KEY (job_id) REFERENCES discord_ai_jobs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (id) VALUES ('004_discord_ai');
