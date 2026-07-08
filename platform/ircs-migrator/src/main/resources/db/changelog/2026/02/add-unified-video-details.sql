-- liquibase formatted sql

-- changeset zzp84:1778000000000-add-unified-video-details
-- 说明: 为聚合视频 (UnifiedVideo) 补充缺失的元数据字段，以支持更丰富的详情页展示和搜索

ALTER TABLE unified_videos ADD COLUMN sub_title VARCHAR(255);
ALTER TABLE unified_videos ADD COLUMN total_episodes VARCHAR(50);
ALTER TABLE unified_videos ADD COLUMN duration VARCHAR(50);
ALTER TABLE unified_videos ADD COLUMN remarks VARCHAR(255);

-- 为 sub_title 创建 GIN 索引 (依赖 pg_trgm)，提升别名搜索性能
CREATE INDEX idx_unified_videos_sub_title_trgm ON unified_videos USING gin (sub_title gin_trgm_ops);