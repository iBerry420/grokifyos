ALTER TABLE discord_ai_jobs
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'tag' AFTER bot_id,
    ADD COLUMN summary MEDIUMTEXT NULL AFTER label,
    ADD KEY idx_discord_ai_jobs_kind_time (kind, created_at);

INSERT IGNORE INTO schema_migrations (id) VALUES ('005_discord_ai_kind');
