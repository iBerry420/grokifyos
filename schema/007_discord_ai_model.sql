ALTER TABLE discord_ai_jobs
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'spacexai',
    ADD COLUMN model VARCHAR(80) NOT NULL DEFAULT 'grok-4.6',
    ADD COLUMN reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'high';

INSERT IGNORE INTO schema_migrations (id) VALUES ('007_discord_ai_model');
