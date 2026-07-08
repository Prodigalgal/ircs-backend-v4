-- liquibase formatted sql

-- changeset zzp84:1767720000000-1
ALTER TABLE videos ADD COLUMN playlist_retry_count INTEGER DEFAULT 0;

-- changeset zzp84:1767720000000-2
CREATE INDEX idx_videos_playlist_retry ON videos (playlist_retry_count);