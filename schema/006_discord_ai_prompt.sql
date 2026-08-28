ALTER TABLE discord_ai_jobs
    ADD COLUMN prompt TEXT NULL AFTER summary;

INSERT IGNORE INTO schema_migrations (id) VALUES ('006_discord_ai_prompt');
