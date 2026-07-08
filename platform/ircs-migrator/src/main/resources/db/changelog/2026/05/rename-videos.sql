-- liquibase formatted sql

-- changeset antigravity:rename-videos-to-raw-videos
ALTER TABLE videos RENAME TO raw_videos;

ALTER INDEX idx_videos_created_at RENAME TO idx_raw_videos_created_at;
ALTER INDEX idx_videos_norm_status RENAME TO idx_raw_videos_norm_status;
ALTER INDEX idx_videos_enrich_status RENAME TO idx_raw_videos_enrich_status;
ALTER INDEX idx_videos_ds_cat_id RENAME TO idx_raw_videos_ds_cat_id;
ALTER INDEX idx_videos_data_source_id RENAME TO idx_raw_videos_data_source_id;
ALTER INDEX idx_videos_douban_id RENAME TO idx_raw_videos_douban_id;
ALTER INDEX idx_videos_tmdb_id RENAME TO idx_raw_videos_tmdb_id;
ALTER INDEX idx_videos_playlist_retry RENAME TO idx_raw_videos_playlist_retry;
ALTER INDEX idx_videos_unified_video_id RENAME TO idx_raw_videos_unified_video_id;
ALTER INDEX idx_videos_cover_image_id RENAME TO idx_raw_videos_cover_image_id;
