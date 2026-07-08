-- liquibase formatted sql
-- changeset antigravity:add-unified-video-season-subtitle
ALTER TABLE unified_videos ADD COLUMN IF NOT EXISTS season INTEGER;
ALTER TABLE unified_videos ADD COLUMN IF NOT EXISTS subtitle VARCHAR(255);

-- changeset antigravity:add-raw-video-season-subtitle
ALTER TABLE raw_videos ADD COLUMN IF NOT EXISTS season INTEGER;
ALTER TABLE raw_videos ADD COLUMN IF NOT EXISTS subtitle VARCHAR(255);
