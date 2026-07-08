-- liquibase formatted sql

-- changeset zzp84:1768400000000-add-missing-indexes
-- 说明: 补充核心外键和关联表的索引，解决详情页查询 Full Scan 问题

-- 1. Playlists -> Video (OneToMany)
-- 用于: findWithDetailsByVideoId (WHERE p.video.id = ?)
CREATE INDEX IF NOT EXISTS idx_playlists_video_id ON playlists (video_id);

-- 2. Episodes -> Playlist (OneToMany)
-- 用于: findWithDetailsByVideoId (FETCH p.episodes)
CREATE INDEX IF NOT EXISTS idx_episodes_playlist_id ON episodes (playlist_id);

-- 3. Episodes -> SourceDomain (ManyToOne)
-- 用于: findWithDetailsByVideoId (FETCH e.sourceDomain)
CREATE INDEX IF NOT EXISTS idx_episodes_source_domain_id ON episodes (source_domain_id);

-- 4. ManyToMany Junction Tables - Reverse Indexes
-- 默认 PK 是 (other_id, video_id)，查询 video details 时是 WHERE video_id = ?，需要反向索引

-- Video <-> Actors
CREATE INDEX IF NOT EXISTS idx_video_actors_video_id ON video_actors (video_id);

-- Video <-> Directors
CREATE INDEX IF NOT EXISTS idx_video_directors_video_id ON video_directors (video_id);

-- Video <-> RawGenres
CREATE INDEX IF NOT EXISTS idx_video_raw_genres_video_id ON video_raw_genres (video_id);

-- Video <-> RawLanguages
CREATE INDEX IF NOT EXISTS idx_video_raw_languages_video_id ON video_raw_languages (video_id);

-- 5. UnifiedVideo Junction Tables (未来聚合查询优化)
CREATE INDEX IF NOT EXISTS idx_uni_video_actors_uni_id ON unified_video_actors (unified_video_id);
CREATE INDEX IF NOT EXISTS idx_uni_video_directors_uni_id ON unified_video_directors (unified_video_id);
CREATE INDEX IF NOT EXISTS idx_uni_video_genres_uni_id ON unified_video_genres (unified_video_id);
CREATE INDEX IF NOT EXISTS idx_uni_video_langs_uni_id ON unified_video_standard_languages (unified_video_id);

-- 6. Other Foreign Keys
CREATE INDEX IF NOT EXISTS idx_videos_unified_video_id ON videos (unified_video_id);
CREATE INDEX IF NOT EXISTS idx_videos_cover_image_id ON videos (cover_image_id);